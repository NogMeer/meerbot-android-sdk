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
 * Класс намеренно не ViewModel: он живёт в [ru.meerbot.sdk.MeerBot] столько же, сколько
 * сессия SDK, и поэтому переживает и поворот экрана, и пересоздание активити — открытый
 * SSE-поток не рвётся. ViewModel здесь была бы лишней ступенью, которая к тому же
 * застревала бы на старом контроллере после повторного `configure(...)`.
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
                client.openSession()
                store.setError(null)
                // История подтягивается всегда: диалог у канала один на устройство, и после
                // переустановки экрана лента обязана прийти с сервера, а не остаться пустой.
                runCatching { loadHistory() }
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

    /**
     * Привести ленту к серверной: вызывается, когда приложение вернулось на передний план
     * или получило пуш от своего бэкенда о новом ответе менеджера.
     *
     * Выбирать диалог клиенту нечем: у канала он один на устройство, сервер резолвит его
     * по токену. Раньше здесь был `openConversation(id)` — вместе с виджетным контрактом
     * ушёл и он.
     */
    fun refresh() {
        scope.launch {
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

    /**
     * Идентификатор диалога — непрозрачен и действителен только в паре с каналом
     * `mobile_app`. Нужен хост-приложению ровно для одного: не показывать свой пуш о
     * диалоге, который открыт на экране.
     */
    val conversationId: Long? get() = client.conversationId

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

        // Ответ мог быть дописан сервером, пока рвалось соединение. Серверную ленту
        // принимаем ТОЛЬКО если она заканчивается ответом: иначе замена выбросила бы из UI
        // недоставленное сообщение пользователя.
        val items = runCatching { fetchHistory() }.getOrNull()
        if (items != null && items.lastOrNull()?.role == "assistant") {
            store.replaceAll(items)
            return
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
    private suspend fun fetchHistory(): List<ChatMessage> {
        val page = client.history()
        store.setMode(page.mode)
        return page.messages.map { item ->
        ChatMessage(
            id = "srv-${item.id}",
            role = item.role,
            // Автор берётся из `authorKind`, а не выводится из наличия подписи: менеджер
            // без имени (учётка без ФИО) считался бы ИИ, то есть ответ живого человека в
            // ленте оказывался бы ответом бота. Отсутствие поля — старая сборка платформы,
            // фолбэк на прежнее правило.
            author = if (item.role == "assistant") {
                when {
                    item.authorKind == "manager" -> "manager"
                    item.authorKind != null -> "ai"
                    item.authorName != null -> "manager"
                    else -> "ai"
                }
            } else null,
            authorName = item.authorName,
            content = item.content,
                timestamp = item.createdAtMs,
            )
        }
    }

    private fun chatError(error: Throwable): ChatError {
        val meerBotError = error as? MeerBotError ?: MeerBotError.Network(error.message ?: "unknown")
        return ChatError(code = meerBotError.code, messageRes = meerBotError.messageRes)
    }
}
