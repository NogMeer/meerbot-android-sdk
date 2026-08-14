package ru.meerbot.sdk.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.ChatStreamEvent
import ru.meerbot.sdk.network.MeerBotError

/**
 * Связка «сеть ↔ состояние экрана».
 *
 * Здесь живут все решения о поведении на границе сети: что делать при обрыве, когда догонять
 * историю, что показывать пользователю. Экран остаётся тонким.
 *
 * Класс намеренно не ViewModel: приложению, которое рисует свой UI, не нужно тащить
 * lifecycle-зависимость. [ChatViewModel] — тонкая обёртка поверх этого контроллера.
 */
class ChatController(
    private val client: ApiClient,
    private val scope: CoroutineScope,
) {

    val store = ChatStore()
    val state: StateFlow<ChatState> get() = store.state

    private var streamJob: Job? = null
    private var startJob: Job? = null

    /** Открыть сессию и подтянуть историю прошлого диалога (если он восстановлен сервером). */
    fun start() {
        // Повторный показ экрана не должен выписывать новый JWT: каждый handshake — это ещё
        // один jti в allowlist сервера и upsert визитора.
        if (startJob?.isActive == true || store.state.value.ready) return
        startJob = scope.launch {
            try {
                val session = client.openSession()
                store.setGreeting(session.greeting)
                store.setMode(session.mode)
                store.setError(null)
                if (session.conversationId != null) {
                    runCatching { loadHistory() }
                }
                store.setReady(true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                store.setReady(false)
                store.setError(chatError(e))
            }
        }
    }

    fun setDraft(text: String) = store.setDraft(text)

    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || store.sending || store.mode == ChatMode.Closed) return
        store.setRetryable(null)
        store.clearDraft()
        val userMessage = store.appendUserMessage(trimmed)
        run(trimmed, userMessage.id)
    }

    /** Повторить последнюю неудачную отправку. */
    fun retry() {
        val text = store.state.value.retryable ?: return
        store.setRetryable(null)
        // Прошлое сообщение осталось в ленте помеченным как недоставленное — переиспользуем его.
        val failed = store.messages.lastOrNull { it.failed && it.role == "user" }
        if (failed != null) {
            store.setFailed(failed.id, false)
            run(text, failed.id)
        } else {
            send(text)
        }
    }

    /** Открыть конкретный диалог (deep link из пуша) и подтянуть его историю. */
    fun openConversation(id: Long) {
        scope.launch {
            client.setConversationId(id)
            try {
                loadHistory()
                store.setError(null)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                store.setError(chatError(e))
            }
        }
    }

    fun stop() {
        streamJob?.cancel()
        streamJob = null
        startJob?.cancel()
        startJob = null
        store.setSending(false)
    }

    // ─── Поток ────────────────────────────────────────────────────────────────────────────

    private fun run(text: String, userMessageId: String) {
        streamJob?.cancel()
        store.setError(null)
        store.setSending(true)
        val placeholderId = store.appendAssistantPlaceholder().id

        streamJob = scope.launch {
            try {
                client.sendMessage(text).collect { handle(it, placeholderId) }
                store.finalizeAssistant(placeholderId)
                store.dropEmptyPlaceholder(placeholderId)
                store.setSending(false)
            } catch (e: CancellationException) {
                // Отмена приходит либо от новой отправки, либо от stop(): свой плейсхолдер
                // подчищаем, но общий флаг отправки не трогаем — им уже владеет новая задача.
                store.finalizeAssistant(placeholderId)
                store.dropEmptyPlaceholder(placeholderId)
                throw e
            } catch (e: Throwable) {
                handleFailure(e, text, userMessageId, placeholderId)
            }
        }
    }

    private fun handle(event: ChatStreamEvent, placeholderId: String) {
        when (event) {
            is ChatStreamEvent.Meta -> store.setMode(event.mode)

            is ChatStreamEvent.ContentDelta ->
                store.updateAssistantContent(placeholderId, event.text)

            is ChatStreamEvent.Done -> {
                store.finalizeAssistant(placeholderId)
                store.dropEmptyPlaceholder(placeholderId)
                store.setSending(false)
            }

            is ChatStreamEvent.Manager -> {
                store.appendOperatorMessage(event.message.text, event.message.authorName)
                store.setOperatorTyping(null)
            }

            is ChatStreamEvent.Escalation -> store.setMode(ChatMode.PendingEscalation)

            is ChatStreamEvent.ForwardedToManager -> store.setMode(event.mode)

            // Живое соединение — снимаем баннер предыдущей ошибки.
            is ChatStreamEvent.Heartbeat -> store.setError(null)

            is ChatStreamEvent.ServerError -> {
                store.finalizeAssistant(placeholderId)
                store.dropEmptyPlaceholder(placeholderId)
                store.setSending(false)
                store.setError(chatError(MeerBotError.Stream(event.code, event.message)))
            }

            is ChatStreamEvent.Timeout -> {
                store.finalizeAssistant(placeholderId)
                store.setSending(false)
            }

            is ChatStreamEvent.Shutdown -> {
                // Плановый рестарт сервера — не сетевой сбой. Ответ уже могли дописать в БД.
                store.finalizeAssistant(placeholderId)
                store.setSending(false)
                scope.launch { runCatching { loadHistory() } }
            }

            is ChatStreamEvent.Unknown -> Unit
        }
    }

    /**
     * Обрыв или ошибка транспорта. Частично полученный текст не выбрасываем, состояние
     * пытаемся привести к серверному: если диалог уже заведён — перечитываем ленту.
     */
    private suspend fun handleFailure(
        error: Throwable,
        text: String,
        userMessageId: String,
        placeholderId: String,
    ) {
        store.setSending(false)
        store.finalizeAssistant(placeholderId)
        store.dropEmptyPlaceholder(placeholderId)

        if (error is MeerBotError.Cancelled) return

        store.setError(chatError(error))

        // Диалог мог быть уже заведён, а ответ — дописан сервером, пока рвалось соединение.
        // Серверную ленту принимаем ТОЛЬКО если она заканчивается ответом: иначе замена
        // выбросила бы из UI недоставленное сообщение пользователя.
        if (client.conversationId != null) {
            val items = runCatching { fetchHistory() }.getOrNull()
            if (items != null && items.lastOrNull()?.role == "assistant") {
                store.replaceAll(items)
                return
            }
        }

        store.setFailed(userMessageId, true)
        store.setRetryable(text)
    }

    /** Догон истории: сервер — источник правды, локальную ленту заменяем целиком. */
    private suspend fun loadHistory() {
        val items = fetchHistory()
        if (items.isEmpty()) return
        store.replaceAll(items)
    }

    /**
     * Полный тред диалога с сервера (не инкремент — иначе замена ленты обрезала бы её до
     * пары последних сообщений).
     */
    private suspend fun fetchHistory(): List<ChatMessage> = client.history().map { item ->
        ChatMessage(
            id = "srv-${item.id}",
            role = item.role,
            author = if (item.role == "assistant") "ai" else null,
            content = item.content,
            timestamp = item.createdAtMs,
        )
    }

    private fun chatError(error: Throwable): ChatError {
        val meerBotError = error as? MeerBotError ?: MeerBotError.Network(error.message ?: "unknown")
        return ChatError(code = meerBotError.code, messageRes = meerBotError.messageRes)
    }
}
