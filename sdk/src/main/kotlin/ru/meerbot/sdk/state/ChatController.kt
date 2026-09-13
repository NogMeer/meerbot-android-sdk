package ru.meerbot.sdk.state

import androidx.annotation.MainThread
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.ChatStreamEvent
import ru.meerbot.sdk.network.HistoryMessage
import ru.meerbot.sdk.network.MeerBotError
import java.util.concurrent.atomic.AtomicInteger

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
 *
 * Потоки: методы управления — только с главного потока, а `scope` обязан диспетчеризовать на
 * него же (у `MeerBot` — `Dispatchers.Main.immediate`). Задачи и флаги экрана — обычные поля:
 * `stop()` с чужого потока гонялся бы со `start()` экрана (двойной старт, потерянный
 * `streamJob`, и поток прежнего пользователя писал бы в очищенную ленту).
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

    /**
     * Фоновый догон остановлен: сессия не восстанавливается (`device_not_found` и `jwt_*` после
     * уже сделанного переподключения). Без остановки каждый тик — рукопожатие и две истории,
     * вечно и молча. Снимается повторным открытием экрана или удачным догоном по явному
     * действию (отправка, `refresh()`). Возврат из фона действием пользователя в чате не
     * считается и остановленный догон не будит (паритет с iOS).
     */
    private var catchUpSuspended = false

    /**
     * Эпоха identity: растёт на каждой смене пользователя. Запрос ленты, отправленный в
     * прежней эпохе, своего ответа в ленту не кладёт — иначе догон, стартовавший до выхода,
     * вернул бы в очищенную ленту переписку прежнего пользователя. Отмены задач для этого
     * мало: часть догонов запускается без хранимого `Job`.
     */
    private val identityEpoch = AtomicInteger()

    internal companion object {
        /** Периоды догона — те же, что у веб-виджета. `var` ради тестов (там 50 мс). */
        var managerPollIntervalMs = 6_000L
        var idlePollIntervalMs = 12_000L
        /** Потолок страниц за один догон: цикл не имеет права стать бесконечным. */
        const val MAX_CATCH_UP_PAGES = 5
    }

    /** Открыть сессию и подтянуть историю прошлого диалога (если он восстановлен сервером). */
    @MainThread
    fun start() {
        screenVisible = true
        catchUpSuspended = false
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
        val startedEpoch = identityEpoch.get()
        startJob = scope.launch {
            try {
                client.openSession()
                // Пока шло рукопожатие, сменился пользователь: состоянием владеет уже новый старт.
                if (identityEpoch.get() != startedEpoch) return@launch
                store.setError(null)
                // История подтягивается всегда: диалог у канала один на устройство, и после
                // переустановки экрана лента обязана прийти с сервера, а не остаться пустой.
                runCatching { loadHistory() }
                if (identityEpoch.get() != startedEpoch) return@launch
                store.setReady(true)
                startPolling()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (identityEpoch.get() != startedEpoch) return@launch
                store.setReady(false)
                store.setError(chatError(e))
            }
        }
    }

    /**
     * Сменился пользователь (`MeerBot.identify`): лента прежнего уходит сразу, не дожидаясь
     * сети. Задачи прежней сессии отменяются, `ready` сбрасывается — следующее открытие
     * обязано пройти рукопожатие заново. Если экран сейчас на виду, он перезапускается сам;
     * закрытый экран сети не трогает.
     */
    @MainThread
    internal fun resetForIdentityChange() {
        val wasVisible = screenVisible
        identityEpoch.incrementAndGet()
        catchUpSuspended = false
        stop()
        store.resetForIdentityChange()
        if (wasVisible) start()
    }

    fun setDraft(text: String) = store.setDraft(text)

    @MainThread
    fun send(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty() || store.sending || store.mode == ChatMode.Closed) return
        store.setRetryable(null)
        store.clearDraft()
        val userMessage = store.appendUserMessage(trimmed)
        run(trimmed, userMessage.id)
    }

    /**
     * Повторить последнюю неудачную отправку.
     *
     * Повторяется СТРОКА ленты, помеченная недоставленной, и её же текст. Нет такой строки —
     * повторять нечего: пометку снимает только слияние, узнавшее сообщение на сервере, и
     * устаревший текст «Повторить» просто снимается. Раньше здесь стоял запасной `send(text)`,
     * и повтор после такого слияния отправлял уже доставленное сообщение второй раз — с дублем
     * в треде и вторым платным ответом модели. Паритет с iOS.
     */
    @MainThread
    fun retry() {
        if (store.state.value.retryable == null) return
        store.setRetryable(null)
        val failed = store.messages.lastOrNull { it.failed && it.role == "user" } ?: return
        store.setFailed(failed.id, false)
        store.markResent(failed.id)
        run(failed.content, failed.id)
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
    @MainThread
    fun onEnterForeground() {
        // Остановленный догон не будим: сессия не восстановилась, и каждый возврат из фона
        // снова делал бы рукопожатие и две истории.
        if (!screenVisible || !store.state.value.ready || catchUpSuspended) return
        scope.launch { catchUp(silent = true) }
        startPolling()
    }

    /** Ушли в фон: опрос останавливаем — там он даёт только трафик. */
    @MainThread
    fun onEnterBackground() = stopPolling()

    /**
     * Идентификатор диалога — непрозрачен и действителен только в паре с каналом
     * `mobile_app`. Нужен хост-приложению ровно для одного: не показывать свой пуш о
     * диалоге, который открыт на экране.
     */
    val conversationId: Long? get() = client.conversationId

    @MainThread
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
        if (catchUpSuspended || pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (isActive && !catchUpSuspended) {
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
        val startedEpoch = identityEpoch.get()

        try {
            // Цикл `for` с `break`, а НЕ `repeat { … return@repeat }`: последнее возвращает
            // из лямбды, то есть продолжает перебор — догон честно ходил бы за пятой
            // страницей после первой же исчерпывающей. Поймано тестом на числе запросов.
            for (page in 1..MAX_CATCH_UP_PAGES) {
                // Отмена проверяется ПЕРЕД каждой страницей: закрытый экран не должен
                // дочитывать длинную ленту. Уже отправленный запрос при этом долетит —
                // оборвать его на полпути нечем, да и незачем: ответ просто отбрасывается.
                // Паритет с iOS (`ChatController.catchUp`).
                if (!currentCoroutineContext().isActive) return
                val cursor = store.lastServerMessageId
                val response = client.history(since = if (cursor > 0) cursor else null, limit = 50)
                if (identityEpoch.get() != startedEpoch) return
                store.setMode(response.mode)
                store.mergeServerMessages(mapHistory(response.messages))
                if (!response.hasMore) break
            }
            // Баннер снимаем, только если повторять нечего: иначе с экрана исчезла бы кнопка
            // «Повторить» вместе с объяснением, почему она там.
            if (store.state.value.retryable == null) store.setError(null)
            // Сессия снова в порядке (догон по явному действию) — фоновый догон возвращается.
            if (catchUpSuspended) {
                catchUpSuspended = false
                if (screenVisible) startPolling()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (identityEpoch.get() != startedEpoch) return
            if (e is MeerBotError && e.isExpiredToken) {
                // Клиент уже переподключился один раз, и сервер снова не признал устройство.
                // Молча крутить это каждые 6–12 секунд нельзя: догон встаёт до явного действия,
                // а экран говорит правду даже для фонового тика.
                catchUpSuspended = true
                stopPolling()
                store.setError(chatError(e))
                return
            }
            if (!silent) store.setError(chatError(e))
        }
    }

    // ─── Поток ────────────────────────────────────────────────────────────────────────────

    private fun run(text: String, userMessageId: String) {
        streamJob?.cancel()
        store.setError(null)
        store.setSending(true)
        val placeholderId = store.appendAssistantPlaceholder().id
        // Эпоха — на момент отправки, а не провала: иначе смена пользователя, случившаяся во
        // время отправки, не отличалась бы от провала в той же сессии.
        val sentInEpoch = identityEpoch.get()

        streamJob = scope.launch {
            try {
                client.sendMessage(text).collect { handle(it, userMessageId, placeholderId) }
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
                handleFailure(e, text, userMessageId, placeholderId, sentInEpoch)
            }
        }
    }

    private fun handle(event: ChatStreamEvent, userMessageId: String, placeholderId: String) {
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
                // История вливается, а не заменяет ленту, поэтому недописанный пузырь убираем
                // сами, если сервер ответ дописал. Смена пользователя между шагами безопасна:
                // `fetchHistory` сверяет эпоху, а id прежней ленты в новой не найдутся.
                store.finalizeAssistant(placeholderId)
                store.setSending(false)
                scope.launch {
                    runCatching { loadHistory() }
                    settleInterruptedReply(userMessageId, placeholderId)
                }
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
        sentInEpoch: Int,
    ) {
        store.setSending(false)
        store.finalizeAssistant(placeholderId)
        store.dropEmptyPlaceholder(placeholderId)

        // Пользователь сменился: сообщение принадлежит прежнему, его новой ленте ни баннер, ни
        // «Повторить» не нужны. Только это и делает отмену безобидной.
        if (identityEpoch.get() != sentInEpoch) return
        val startedEpoch = sentInEpoch

        store.setError(chatError(error))

        // Отмена в той же эпохе — рукопожатие исчерпало попытки, пока хост менял токены. Сессии
        // нет, сообщение не ушло: молча проглотить её значило бы потерять его без следа.
        if (error is MeerBotError.Cancelled) {
            store.setFailed(userMessageId, true)
            store.setRetryable(text)
            return
        }

        // Ответ мог быть дописан сервером, пока рвалось соединение. Историю вливаем, а
        // доставкой считаем только эхо ЭТОГО сообщения с ответом после него. Раньше хватало
        // «лента кончается ответом»: прошлый ответ бота выдавал недошедшее сообщение за
        // доставленное, замена ленты стирала его, и «Повторить» не было.
        // Диалога нет (сервер не прислал `meta`) — сообщение до него не дошло, и лишний запрос
        // истории ничего не решил бы (паритет с iOS).
        val items = if (client.conversationId != null) runCatching { fetchHistory() }.getOrNull() else null
        // Пользователь сменился, пока шёл запрос: его новой ленте чужой «Повторить» не нужен.
        if (identityEpoch.get() != startedEpoch) return
        if (items != null) {
            store.mergeServerMessages(items)
            if (settleInterruptedReply(userMessageId, placeholderId)) return
        }

        store.setFailed(userMessageId, true)
        store.setRetryable(text)
    }

    /**
     * История треда, ВЛИТАЯ в ленту.
     *
     * Не замена: пользователь пишет, как только открылся экран, и ответ стартовой истории
     * приходит уже после отправки. Замена убирала с экрана отправленное сообщение и
     * стримящийся ответ — при том что сообщение могло уже дойти до сервера. Слияние
     * сохраняет неподтверждённые строки, узнаёт эхо своих и ставит историю над ними.
     */
    private suspend fun loadHistory() {
        val items = fetchHistory() ?: return
        store.mergeServerMessages(items)
    }

    /**
     * Сервер дописал ответ на прерванную отправку: сообщение получило серверный id, и после
     * него есть серверный ответ. Недописанный локальный пузырь тогда лишний — серверная
     * версия ответа уже в ленте, и без удаления пользователь видел бы ответ дважды.
     *
     * Звать ПОСЛЕ слияния истории. Серверный id сообщения засчитывается, только если строка
     * записана после отправки: слияние не отдаёт отправке старую строку с тем же текстом, а
     * проверка здесь держит это правило и на случай его поломки — иначе вчерашнее «да» с
     * ответом после него выдало бы недошедшее сообщение за доставленное без «Повторить».
     */
    private fun settleInterruptedReply(userMessageId: String, placeholderId: String): Boolean {
        val feed = store.messages
        val userServerId = feed.firstOrNull { it.id == userMessageId }?.serverId ?: return false
        if (!store.isAfterSend(userMessageId, userServerId)) return false
        if (feed.none { it.role == "assistant" && (it.serverId ?: 0L) > userServerId }) return false
        if (feed.firstOrNull { it.id == placeholderId }?.serverId == null) {
            store.removeMessage(placeholderId)
        }
        return true
    }

    /**
     * Хвост треда с сервера (без курсора) — для слияния с лентой при старте и после обрыва.
     * `null` — пока шёл запрос, сменился пользователь, и страница принадлежит прежнему.
     */
    private suspend fun fetchHistory(): List<ChatMessage>? {
        val startedEpoch = identityEpoch.get()
        val page = client.history()
        if (identityEpoch.get() != startedEpoch) return null
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
