package ru.meerbot.sdk.state

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID

/**
 * Состояние экрана чата. Контракт совпадает с iOS ChatStore и RN reducer.
 *
 * Отличие от iOS: `ready`/`retryable` живут здесь, а не на контроллере — в Compose один
 * StateFlow на экран дешевле двух подписок, а поля всё равно нужны только UI.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,               // "user" | "assistant" | "system"
    val author: String? = null,     // "ai" | "manager" | null
    val authorName: String? = null,
    val content: String,
    val streaming: Boolean = false,
    /** Сообщение не доставлено (обрыв сети при отправке) — UI показывает возможность повтора. */
    val failed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * id строки на сервере — ключ слияния при догоне ленты.
     *
     * Отдельно от `id`: тот обязан существовать с первого кадра, ещё до отправки
     * (оптимистичное сообщение пользователя). `null` — строка пока живёт только на устройстве.
     * Параметр последний в списке: позиционные вызовы у хостов не должны сломаться.
     */
    val serverId: Long? = null,
)

enum class ChatMode(val raw: String) {
    Ai("ai"),
    PendingEscalation("pending_escalation"),
    Human("human"),
    Closed("closed");

    companion object {
        fun from(raw: String?, fallback: ChatMode = Ai): ChatMode =
            entries.firstOrNull { it.raw == raw } ?: fallback
    }
}

/**
 * Ошибка для экрана: машинный код (для аналитики хоста) и ресурс текста.
 * Текст берётся ресурсом, а не строкой, чтобы следовать локали устройства.
 */
data class ChatError(
    val code: String,
    @StringRes val messageRes: Int,
)

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val mode: ChatMode = ChatMode.Ai,
    val operatorTyping: String? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val connectionError: ChatError? = null,
    /** Приветствие канала из handshake — показывается вместо дефолтной заглушки. */
    val greeting: String? = null,
    /** Handshake выполнен — можно отправлять. */
    val ready: Boolean = false,
    /** Текст, который не удалось отправить: UI показывает «Повторить». */
    val retryable: String? = null,
    /** Наибольший серверный id в ленте — курсор догона (`GET /mobile/messages?since=`). */
    val lastServerMessageId: Long = 0L,
)

class ChatStore {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    val messages: List<ChatMessage> get() = _state.value.messages
    val mode: ChatMode get() = _state.value.mode
    val sending: Boolean get() = _state.value.sending

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun clearDraft() = _state.update { it.copy(draft = "") }

    fun setMode(mode: ChatMode) = _state.update { it.copy(mode = mode) }

    fun setOperatorTyping(name: String?) = _state.update { it.copy(operatorTyping = name) }

    fun setError(error: ChatError?) = _state.update { it.copy(connectionError = error) }

    fun setSending(value: Boolean) = _state.update { it.copy(sending = value) }

    fun setGreeting(text: String?) = _state.update { it.copy(greeting = text) }

    fun setReady(value: Boolean) = _state.update { it.copy(ready = value) }

    fun setRetryable(text: String?) = _state.update { it.copy(retryable = text) }

    fun appendUserMessage(content: String): ChatMessage {
        val msg = ChatMessage(role = "user", content = content)
        _state.update { it.copy(messages = it.messages + msg) }
        return msg
    }

    fun appendAssistantPlaceholder(): ChatMessage {
        val msg = ChatMessage(role = "assistant", author = "ai", content = "", streaming = true)
        _state.update { it.copy(messages = it.messages + msg) }
        return msg
    }

    fun updateAssistantContent(id: String, delta: String) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id == id) m.copy(content = m.content + delta) else m
            }
        )
    }

    fun finalizeAssistant(id: String) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id == id) m.copy(streaming = false) else m
            }
        )
    }

    fun appendOperatorMessage(content: String, authorName: String?) = _state.update {
        it.copy(
            messages = it.messages + ChatMessage(
                role = "assistant",
                author = "manager",
                authorName = authorName,
                content = content,
            )
        )
    }

    /** Пометить сообщение недоставленным (обрыв сети) либо снять пометку при повторе. */
    fun setFailed(id: String, value: Boolean) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id == id) m.copy(failed = value) else m
            }
        )
    }

    /** Убрать пустой стриминговый плейсхолдер (ответ так и не начался). */
    fun dropEmptyPlaceholder(id: String) = _state.update { current ->
        current.copy(messages = current.messages.filterNot { it.id == id && it.content.isEmpty() })
    }

    val lastServerMessageId: Long get() = _state.value.lastServerMessageId

    /** Заменить всю ленту (догон истории с сервера — сервер источник правды). */
    fun replaceAll(items: List<ChatMessage>) = _state.update {
        it.copy(messages = items, lastServerMessageId = bumpCursor(it.lastServerMessageId, items))
    }

    /**
     * Влить серверную страницу в ленту. Идемпотентно по `serverId`. Зеркало iOS
     * `ChatStore.mergeServerMessages`.
     *
     * Три случая, и порядок между ними важен:
     *   1. `serverId` уже в ленте — пропускаем (повторная страница догона — это норма);
     *   2. есть локальный двойник (тот же `role` и текст, ещё без серверного id) — ПРОМОУТИМ
     *      его, а не добавляем второй: иначе своё же сообщение пользователь увидит дважды,
     *      как только догон принесёт его с сервера;
     *   3. иначе — новое сообщение, добавляем в конец.
     *
     * Курсор двигается ВСЕГДА, даже если вся страница пропущена: иначе следующий догон
     * запросил бы те же строки и цикл никогда бы не сдвинулся.
     *
     * @return сколько сообщений реально появилось в ленте.
     */
    fun mergeServerMessages(items: List<ChatMessage>): Int {
        var added = 0
        _state.update { current ->
            val merged = current.messages.toMutableList()
            for (item in items) {
                if (item.serverId != null && merged.any { it.serverId == item.serverId }) continue
                val localIdx = merged.indexOfLast {
                    it.serverId == null && it.role == item.role && it.content == item.content
                }
                if (localIdx >= 0) {
                    merged[localIdx] = merged[localIdx].copy(
                        serverId = item.serverId,
                        failed = false,
                        streaming = false,
                    )
                    continue
                }
                merged += item
                added++
            }
            current.copy(
                messages = merged,
                lastServerMessageId = bumpCursor(current.lastServerMessageId, items),
            )
        }
        return added
    }

    /** Курсор только растёт: страница старее текущего значения не имеет права его откатить. */
    private fun bumpCursor(current: Long, items: List<ChatMessage>): Long =
        maxOf(current, items.mapNotNull { it.serverId }.maxOrNull() ?: 0L)

    fun resetForLogout() {
        _state.value = ChatState()
    }
}
