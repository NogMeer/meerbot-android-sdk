package ru.meerbot.sdk.state

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.ChatStreamEvent
import ru.meerbot.sdk.network.HistoryMessage
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
    private var pollJob: Job? = null
    /** Экран чата на виду. Догон крутится ТОЛЬКО когда экран открыт и сессия готова. */
    private var screenVisible = false

    internal companion object {
        /** Периоды догона — те же, что у веб-виджета. `var` ради тестов (там 50 мс). */
        var managerPollIntervalMs = 6_000L
        var idlePollIntervalMs = 12_000L
        /** Потолок страниц за один догон: цикл не имеет права стать бесконечным. */
        const val MAX_CATCH_UP_PAGES = 5
    }

    /** Открыть сессию и подтянуть историю прошлого диалога (если он восстановлен сервером). */
    fun start() {
        screenVisible = true
        if (startJob?.isActive == true) return

        // Сессия уже поднята: контроллер живёт в синглтоне SDK и переживает закрытие экрана.
        // Второй handshake не нужен (каждый — ещё один jti в allowlist и upsert устройства),
        // НО пока экран был закрыт, менеджер мог ответить. Раньше здесь стоял молчаливый
        // выход, и повторное открытие чата ленту не перечитывало вовсе.
        if (store.state.value.ready) {
            scope.launch { catchUp(silent = true) }
            startPolling()
            return
        }
        startJob = scope.launch {
            try {
                client.openSession()
                store.setError(null)
                // История подтягивается всегда: диалог у канала один на устройство, и после
                // переустановки экрана лента обязана прийти с сервера, а не остаться пустой.
                runCatching { loadHistory() }
                store.setReady(true)
                startPolling()
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
        scope.launch { catchUp(silent = false) }
    }

    /**
     * Приложение вернулось на передний план: догоняем немедленно, не дожидаясь тика.
     * Зовётся экраном SDK; хосту со своим UI доступен через `MeerBot.chatController()`.
     */
    fun onEnterForeground() {
        if (!screenVisible || !store.state.value.ready) return
        scope.launch { catchUp(silent = true) }
        startPolling()
    }

    /** Ушли в фон: опрос останавливаем — там он даёт только трафик. */
    fun onEnterBackground() = stopPolling()

    /**
     * Идентификатор диалога — непрозрачен и действителен только в паре с каналом
     * `mobile_app`. Нужен хост-приложению ровно для одного: не показывать свой пуш о
     * диалоге, который открыт на экране.
     */
    val conversationId: Long? get() = client.conversationId

    fun stop() {
        screenVisible = false
        stopPolling()
        streamJob?.cancel()
        streamJob = null
        startJob?.cancel()
        startJob = null
        store.setSending(false)
        // `ready` СОЗНАТЕЛЬНО не сбрасываем: сессия жива, и следующее открытие экрана
        // обойдётся догоном вместо новой регистрации устройства.
    }

    // ─── Догон ленты ──────────────────────────────────────────────────────────────────────

    /**
     * Пока экран открыт, лента подтягивается сама.
     *
     * Это ЕДИНСТВЕННЫЙ надёжный канал «менеджер ответил → пользователь увидел»: поток живёт
     * только на время ответа бота, а пуш зависит от бэкенда интегратора. Период считается на
     * каждом витке, поэтому переход диалога к человеку ускоряет догон со следующего тика.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive) {
                val mode = store.mode
                val interval =
                    if (mode == ChatMode.Human || mode == ChatMode.PendingEscalation) {
                        managerPollIntervalMs
                    } else {
                        idlePollIntervalMs
                    }
                delay(interval)
                catchUp(silent = true)
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /**
     * Подтянуть всё, что появилось после нашего курсора.
     *
     * `silent` — фоновый тик: его ошибки НЕ красят экран. Оборванная сеть у человека, который
     * просто смотрит переписку, не повод показывать «нет связи»; настоящую ошибку он увидит
     * при отправке.
     *
     * Во время отправки догон не идёт: серверная страница принесла бы половину ещё стримящегося
     * ответа и подралась бы с плейсхолдером.
     */
    private suspend fun catchUp(silent: Boolean) {
        val state = store.state.value
        if (!state.ready || state.sending) return

        try {
            // Цикл `for` с `break`, а НЕ `repeat { … return@repeat }`: последнее возвращает
            // из лямбды, то есть продолжает перебор — догон честно ходил бы за пятой
            // страницей после первой же исчерпывающей. Поймано тестом на числе запросов.
            for (page in 1..MAX_CATCH_UP_PAGES) {
                val cursor = store.lastServerMessageId
                val response = client.history(since = if (cursor > 0) cursor else null, limit = 50)
                store.setMode(response.mode)
                store.mergeServerMessages(mapHistory(response.messages))
                if (!response.hasMore) break
            }
            // Баннер снимаем, только если повторять нечего: иначе с экрана исчезла бы кнопка
            // «Повторить» вместе с объяснением, почему она там.
            if (store.state.value.retryable == null) store.setError(null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!silent) store.setError(chatError(e))
        }
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
                // Разовый догон сразу после потока: он проставляет серверные id только что
                // отправленному сообщению и ответу. Без него первый тик поллинга принёс бы
                // обе строки как «новые», и слияние держалось бы на совпадении текста.
                catchUp(silent = true)
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
     * Хвост треда с сервера (без курсора) — для полной замены ленты при старте и после обрыва.
     */
    private suspend fun fetchHistory(): List<ChatMessage> {
        val page = client.history()
        store.setMode(page.mode)
        return mapHistory(page.messages)
    }

    private fun mapHistory(items: List<HistoryMessage>): List<ChatMessage> =
        items.map { item ->
            ChatMessage(
                id = "srv-${item.id}",
                serverId = item.id,
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

    private fun chatError(error: Throwable): ChatError {
        val meerBotError = error as? MeerBotError ?: MeerBotError.Network(error.message ?: "unknown")
        return ChatError(code = meerBotError.code, messageRes = meerBotError.messageRes)
    }
}
