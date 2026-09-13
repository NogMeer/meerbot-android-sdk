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

    /**
     * Дописать кусок потока. Пробелы В НАЧАЛЕ ответа отбрасываются, пока текст пуст.
     *
     * Модель начинает ответ с перевода строки чаще, чем кажется (стабильно — на ответе про
     * передачу менеджеру). Сервер такой ответ сохраняет уже подрезанным, поэтому лишний
     * `\n` жил только на устройстве: пузырь начинался с пустой строки, а догон ленты не
     * узнавал в нём свою же строку и клал серверную копию рядом — сообщение двоилось.
     */
    fun updateAssistantContent(id: String, delta: String) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id != id) m
                else m.copy(content = if (m.content.isEmpty()) m.content + delta.trimStart() else m.content + delta)
            }
        )
    }

    /** Ответ дописан: хвостовые пробелы убираем — на сервере строка хранится без них. */
    fun finalizeAssistant(id: String) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id == id) m.copy(streaming = false, content = m.content.trimEnd()) else m
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
                val localIdx = indexOfLocalTwin(merged, item)
                if (localIdx >= 0) {
                    merged[localIdx] = promoted(merged[localIdx], item)
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

    /**
     * Применить хвост треда с сервера (старт экрана, обрыв потока, плановый рестарт сервера).
     *
     * Серверные строки — источник правды, но ЛОКАЛЬНЫЕ (ещё без `serverId`) не выбрасываются:
     * сообщение, отправленное до прихода стартовой истории, ещё отправляется или ждёт
     * «Повторить», а снимок, запрошенный раньше отправки, о нём не знает. Раньше здесь была
     * замена ленты целиком, и такое сообщение пропадало с экрана.
     *
     * Дедупликация — то же правило, что у [mergeServerMessages]: строка снимка с локальным
     * двойником промоутит его (id пузыря сохраняется, дубля нет). Одно ограничение сверху:
     * двойником бывает только строка НОВЕЕ курсора. Всё, что не новее, лента уже видела, и
     * совпадение текста со старой строкой («привет» вчера и «привет» сегодня) — не эхо. Догону
     * это ограничение не нужно: его страница по построению новее курсора.
     *
     * Непромоутированная локальная строка встаёт сразу за серверной строкой, за которой стояла
     * в ленте (догон тоже кладёт новое после неё), не было такой в снимке — перед ближайшей
     * следующей, нет и её — в конец.
     *
     * @param supersededLocalId локальная строка, которую снимок заменяет по смыслу, а не по
     *   тексту: обрывок ответа, дописанного сервером целиком. Убирается в том же обновлении
     *   состояния, чтобы экран не увидел кадр без ответа вовсе.
     */
    internal fun mergeServerSnapshot(items: List<ChatMessage>, supersededLocalId: String? = null) =
        _state.update { current ->
            val feed = current.messages.filterNot { it.id == supersededLocalId }
            val snapshot = items.toMutableList()
            val candidates = feed.filter { it.serverId == null }.toMutableList()
            val promotedIds = HashSet<String>()
            // С конца: последнее эхо достаётся последнему двойнику, порядок пузырей сохраняется.
            for (i in snapshot.indices.reversed()) {
                val item = snapshot[i]
                val serverId = item.serverId ?: continue
                if (serverId <= current.lastServerMessageId) continue
                val idx = indexOfLocalTwin(candidates, item)
                if (idx < 0) continue
                val local = candidates.removeAt(idx)
                snapshot[i] = promoted(local, item)
                promotedIds += local.id
            }

            val slotByServerId = HashMap<Long, Int>()
            val slotByLocalId = HashMap<String, Int>()
            snapshot.forEachIndexed { slot, m ->
                m.serverId?.let { slotByServerId[it] = slot }
                if (m.id in promotedIds) slotByLocalId[m.id] = slot
            }
            fun slotOf(m: ChatMessage): Int? =
                m.serverId?.let { slotByServerId[it] } ?: slotByLocalId[m.id]

            val pendingBySlot = HashMap<Int, MutableList<ChatMessage>>()
            feed.forEachIndexed { pos, m ->
                if (m.serverId != null || m.id in promotedIds) return@forEachIndexed
                val slot = (pos - 1 downTo 0).firstNotNullOfOrNull { slotOf(feed[it]) }?.plus(1)
                    ?: (pos + 1 until feed.size).firstNotNullOfOrNull { slotOf(feed[it]) }
                    ?: snapshot.size
                pendingBySlot.getOrPut(slot) { mutableListOf() } += m
            }

            val merged = ArrayList<ChatMessage>(snapshot.size + candidates.size)
            for (slot in 0..snapshot.size) {
                pendingBySlot[slot]?.let { merged += it }
                if (slot < snapshot.size) merged += snapshot[slot]
            }
            current.copy(
                messages = merged,
                lastServerMessageId = bumpCursor(current.lastServerMessageId, items),
            )
        }

    /**
     * Локальный двойник серверной строки: тот же `role` и текст, ещё без серверного id.
     *
     * Сравнение по ПОДРЕЗАННОМУ тексту: сервер хранит ответ без крайних пробелов, а в потоке
     * они приходят (первым чанком часто идёт перевод строки). Точное равенство роняло слияние
     * в дубль ровно на таких ответах.
     */
    private fun indexOfLocalTwin(messages: List<ChatMessage>, item: ChatMessage): Int =
        messages.indexOfLast {
            it.serverId == null && it.role == item.role && it.content.trim() == item.content.trim()
        }

    private fun promoted(local: ChatMessage, item: ChatMessage): ChatMessage =
        local.copy(serverId = item.serverId, failed = false, streaming = false)

    /** Курсор только растёт: страница старее текущего значения не имеет права его откатить. */
    private fun bumpCursor(current: Long, items: List<ChatMessage>): Long =
        maxOf(current, items.mapNotNull { it.serverId }.maxOrNull() ?: 0L)

    fun resetForLogout() {
        _state.value = ChatState()
    }

    /**
     * Сменился пользователь: лента, режим, черновик и курсор принадлежат прежнему и уходят.
     * Приветствие — настройка хоста, а не переписка, поэтому остаётся.
     */
    internal fun resetForIdentityChange() = _state.update { ChatState(greeting = it.greeting) }
}
