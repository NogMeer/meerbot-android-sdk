package ru.meerbot.sdk.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.meerbot.sdk.R
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.MeerBotConfiguration
import ru.meerbot.sdk.testing.ScriptedDispatcher
import java.time.Instant

/** Дождаться состояния подпиской на StateFlow, а не опросом с паузами. */
private fun awaitState(controller: ChatController, timeoutMs: Long, check: (ChatState) -> Boolean) {
    val reached = runBlocking { withTimeoutOrNull(timeoutMs) { controller.state.first(check) } }
    if (reached == null) fail("состояние не дождалось условия: ${controller.state.value}")
}

/** Все задачи scope доработали: отменённые — дошли до конца, новых запросов быть не может. */
private fun awaitIdle(scope: CoroutineScope) = runBlocking {
    withTimeout(5_000) { scope.coroutineContext.job.children.toList().joinAll() }
}

/** ISO-8601 `createdAt` сервера. Эхо сверяется со временем отправки, поэтому «сейчас» — настоящее. */
private fun iso(ms: Long = System.currentTimeMillis()): String = Instant.ofEpochMilli(ms).toString()

private const val DAY_MS = 24 * 60 * 60 * 1000L

/**
 * Поведение на границе сети: что видит пользователь при обрыве, повторе, приходе истории.
 *
 * Старт канала — это ДВА запроса: рукопожатие `/mobile/register` и догон `/mobile/messages`.
 * История подтягивается всегда, потому что диалог у канала один на устройство и сервер —
 * единственный источник правды о нём.
 */
class ChatControllerTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }

    @After
    fun tearDown() {
        ChatController.managerPollIntervalMs = 6_000L
        ChatController.idlePollIntervalMs = 12_000L
        scope.cancel()
        server.shutdown()
    }

    private fun apiClient(): ApiClient = ApiClient(
        config = MeerBotConfiguration(
            apiKey = "pk_live_mobile",
            baseUrl = server.url("/").toString().trimEnd('/'),
            sdkVersion = "0.2.0-test",
        ),
        visitorUuid = "11111111-1111-1111-1111-111111111111",
        installationId = "and-22222222-2222-2222-2222-222222222222",
    )

    private fun controller(client: ApiClient = apiClient()): ChatController =
        ChatController(client = client, scope = scope)

    /** Рукопожатие канала: ни приветствия, ни режима оно не отдаёт — только сессию. */
    private fun register() = MockResponse().setBody(
        """{"deviceId":"42","jwt":"jwt-1","expiresIn":900,"attestationRequired":false,"identity":{"status":"not_provided"}}"""
    )

    /** Догон истории. Режим диалога приходит именно отсюда. */
    private fun history(mode: String = "ai", messages: String = "") = MockResponse().setBody(
        """{"messages":[$messages],"hasMore":false,"mode":"$mode"}"""
    )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private val metaFrame = "event: meta\ndata: {\"conversationId\":3,\"mode\":\"ai\"}\n\n"

    /**
     * Поток, оборванный посреди тела. MockWebServer отдаёт половину тела и рвёт соединение,
     * поэтому после значимых кадров идёт заполнитель: они гарантированно в первой половине.
     */
    private fun brokenStream(frames: String) = sse(
        frames + "data: {\"choices\":[{\"delta\":{\"content\":\"нача\"}}]}\n\n" +
            "event: heartbeat\ndata: {}\n\n".repeat(40),
    ).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)

    /** Стартовать и дождаться готовности: рукопожатие + пустая история. */
    private fun started(
        mode: String = "ai",
        messages: String = "",
        client: ApiClient = apiClient(),
    ): ChatController {
        server.enqueue(register())
        server.enqueue(history(mode = mode, messages = messages))
        val controller = controller(client)
        controller.start()
        await(controller) { it.ready }
        return controller
    }

    private fun await(controller: ChatController, timeoutMs: Long = 5_000, check: (ChatState) -> Boolean) =
        awaitState(controller, timeoutMs, check)

    /** Дождаться, пока доработают все задачи контроллера, — вместо паузы наугад. */
    private fun awaitControllerIdle() = awaitIdle(scope)

    @Test
    fun `старт поднимает сессию и подтягивает прошлую переписку`() {
        val controller = started(
            messages = """{"id":7,"role":"assistant","content":"мы на связи","createdAt":"2026-08-14T10:00:00.000Z"}"""
        )

        assertEquals("мы на связи", controller.state.value.messages.single().content)
        assertEquals("/api/v1/mobile/register", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/mobile/messages"))
    }

    @Test
    fun `режим диалога приходит из истории`() {
        val controller = started(mode = "human")

        assertEquals(ChatMode.Human, controller.state.value.mode)
    }

    /**
     * Экран открыли повторно внутри живого процесса. Рукопожатия второй раз быть не должно
     * (каждое — новый jti в allowlist), но лента ОБЯЗАНА догнаться: пока экран был закрыт,
     * менеджер мог ответить. До 0.2.4 здесь стоял молчаливый выход, и ответ не появлялся
     * до перезапуска приложения.
     */
    @Test
    fun `повторное открытие экрана догоняет ленту без второго рукопожатия`() {
        val controller = started()
        server.enqueue(
            history(
                messages = """{"id":9,"role":"assistant","content":"я тут","authorKind":"manager","authorName":"Роман","createdAt":"2026-08-25T10:00:00.000Z"}""",
            ),
        )

        controller.start()
        await(controller) { it.messages.any { m -> m.content == "я тут" } }

        assertEquals("/api/v1/mobile/register", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/mobile/messages"))
        // Третий запрос — догон, а не второе рукопожатие.
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/mobile/messages"))
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `ответ стримится в ленту и снимает признак отправки`() {
        val controller = started()
        server.enqueue(
            sse(
                metaFrame +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Здрав\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ствуйте\"}}]}\n\n" +
                    "data: [DONE]\n\n"
            )
        )

        controller.send("привет")

        await(controller) { !it.sending && it.messages.size == 2 }
        val state = controller.state.value
        assertEquals("привет", state.messages[0].content)
        assertEquals("Здравствуйте", state.messages[1].content)
        assertTrue(!state.messages[1].streaming)
        assertNull(state.connectionError)
        // Диалог из meta доступен хосту — чтобы он не будил пушем открытый на экране тред.
        assertEquals(3L, controller.conversationId)
    }

    @Test
    fun `обрыв помечает сообщение недоставленным и разрешает повтор`() {
        val controller = started()
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"нача\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        controller.send("привет")

        // Ждём именно `retryable`, а не баннер ошибки: `handleFailure` ставит ошибку СРАЗУ,
        // а пометку недоставленного и текст для повтора — только после сверки с сервером.
        await(controller) { it.retryable != null }
        val state = controller.state.value
        assertEquals("привет", state.retryable)
        assertNotNull(state.connectionError)
        assertTrue(state.messages.first { it.role == "user" }.failed)
        assertTrue(!state.sending)
    }

    /**
     * Сервер не прислал `meta` — диалог не открыт, сообщение до него не дошло. Запрос истории
     * здесь ничего не решил бы (паритет с iOS).
     */
    @Test
    fun `обрыв до открытия диалога историю не запрашивает`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        dispatcher.clearArrivals()
        dispatcher.streamFallback = { ScriptedDispatcher.error(502, "bad_gateway") }

        controller.send("привет")

        await(controller) { it.retryable != null }
        assertEquals(0, dispatcher.historyArrivalCount())
        assertTrue(controller.state.value.messages.single().failed)
    }

    @Test
    fun `повтор доводит ответ и снимает пометку`() {
        val controller = started()
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"готово\"}}]}\n\ndata: [DONE]\n\n"))
        controller.send("привет")
        await(controller) { it.retryable != null }

        controller.retry()

        await(controller) { !it.sending && it.messages.any { m -> m.content == "готово" } }
        val state = controller.state.value
        assertTrue(state.messages.none { it.failed })
        assertNull(state.retryable)
        // Сообщение пользователя переиспользовано, а не продублировано.
        assertEquals(1, state.messages.count { it.role == "user" })
    }

    /**
     * Сообщение ушло, хотя отправка кончилась ошибкой: догон узнал эхо и снял пометку. «Повторить»
     * по устаревшему состоянию (свой UI хоста) раньше падал в `send(text)` и слал доставленное
     * второй раз — дубль в треде и второй платный ответ модели.
     */
    @Test
    fun `повтор доставленного по эху сообщения ничего не отправляет`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val api = apiClient()
        val controller = controller(api)
        controller.start()
        await(controller) { it.ready }
        api.rememberConversationId(3)
        dispatcher.streamFallback = { ScriptedDispatcher.error(502, "bad_gateway") }
        controller.send("привет")
        await(controller) { it.retryable != null }

        dispatcher.historyFallback = {
            ScriptedDispatcher.history(messages = """{"id":7,"role":"user","content":"привет","createdAt":"${iso()}"}""")
        }
        controller.refresh()
        await(controller) { s -> s.messages.single { it.role == "user" }.serverId == 7L }
        assertNull(controller.state.value.retryable)

        dispatcher.clearArrivals()
        controller.store.setRetryable("привет")
        controller.retry()

        val state = controller.state.value
        assertTrue(!state.sending)
        assertNull(state.retryable)
        assertEquals(1, state.messages.count { it.role == "user" })
        controller.stop()
        awaitControllerIdle()
        assertEquals(0, dispatcher.streamArrivalCount())
    }

    /** Недоставленное повторяется ровно один раз, двойной тап второй отправки не делает. */
    @Test
    fun `повтор недоставленного отправляет его ровно один раз`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        dispatcher.streamFallback = { ScriptedDispatcher.error(502, "bad_gateway") }
        controller.send("привет")
        await(controller) { it.retryable != null }

        dispatcher.clearArrivals()
        val gate = dispatcher.gateNextStream(ScriptedDispatcher.sse(answerStream))
        controller.retry()
        controller.retry()
        assertEquals("привет", JSONObject(dispatcher.awaitStream().body.readUtf8()).getString("message"))
        gate.countDown()
        await(controller) { s -> !s.sending && s.messages.any { it.content == "Здравствуйте" } }
        controller.stop()
        awaitControllerIdle()

        assertEquals(0, dispatcher.streamArrivalCount())
        val state = controller.state.value
        assertEquals(1, state.messages.count { it.role == "user" })
        assertTrue(state.messages.none { it.failed })
        assertNull(state.retryable)
    }

    @Test
    fun `серверная история заменяет ленту после обрыва`() {
        val controller = started()
        server.enqueue(brokenStream(metaFrame))
        // Сервер успел дописать ответ, пока рвалось соединение.
        server.enqueue(
            history(
                messages = """{"id":1,"role":"user","content":"привет","createdAt":"${iso()}"},
                   {"id":2,"role":"assistant","content":"ответ дописан","createdAt":"${iso()}"}"""
            )
        )

        controller.send("привет")

        await(controller) { it.messages.any { m -> m.content == "ответ дописан" } }
        val state = controller.state.value
        assertEquals(2, state.messages.size)
        assertTrue(state.messages.none { it.failed })
    }

    @Test
    fun `ответ менеджера в истории не выглядит ответом ИИ`() {
        val controller = started(
            mode = "human",
            messages = """{"id":9,"role":"assistant","content":"уже смотрю","authorName":"Марат","createdAt":"2026-08-14T10:00:00.000Z"}"""
        )

        val message = controller.state.value.messages.single()
        assertEquals("manager", message.author)
        assertEquals("Марат", message.authorName)
    }

    /**
     * Менеджер без подписи (учётка без ФИО) — тоже менеджер.
     *
     * Правило «есть имя → человек» ломалось на таком ответе: пользователь видел живого
     * оператора как бота. Дискриминатор теперь машинный (`authorKind`), имя — только для UI.
     */
    @Test
    fun `менеджер без имени всё равно менеджер`() {
        val controller = started(
            mode = "human",
            messages = """{"id":9,"role":"assistant","content":"уже смотрю","authorKind":"manager","createdAt":"2026-08-14T10:00:00.000Z"}"""
        )

        val message = controller.state.value.messages.single()
        assertEquals("manager", message.author)
        assertNull(message.authorName)
    }

    /** Старая сборка платформы поля не отдаёт — прежнее правило по имени остаётся в силе. */
    @Test
    fun `история без authorKind читается по прежнему правилу`() {
        val controller = started(
            mode = "ai",
            messages = """{"id":9,"role":"assistant","content":"ответ","createdAt":"2026-08-14T10:00:00.000Z"}"""
        )

        assertEquals("ai", controller.state.value.messages.single().author)
    }

    /** Отказ в закрытом диалоге синхронный: ни сообщения, ни задачи, ни запроса. */
    @Test
    fun `в закрытом диалоге отправка не уходит`() {
        val controller = started(mode = "closed")

        controller.send("привет")

        assertEquals(ChatMode.Closed, controller.state.value.mode)
        assertTrue(controller.state.value.messages.isEmpty())
        assertTrue(!controller.state.value.sending)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `refresh подтягивает ответ менеджера, пришедший вне потока`() {
        // Ответ менеджера доезжает вебхуком до бэкенда интегратора, тот будит приложение —
        // и приложение зовёт refresh(). Своей доставки у SDK нет.
        val controller = started()
        server.enqueue(
            history(
                mode = "human",
                messages = """{"id":5,"role":"assistant","content":"вам ответил менеджер","authorName":"Марат","createdAt":"2026-08-14T10:00:00.000Z"}"""
            )
        )

        controller.refresh()

        await(controller) { it.messages.any { m -> m.content == "вам ответил менеджер" } }
        assertEquals(ChatMode.Human, controller.state.value.mode)
    }

    // ─── Смена пользователя ───────────────────────────────────────────────────────────────

    private val previousUserMessage =
        """{"id":7,"role":"user","content":"мой номер заказа 123","createdAt":"2026-08-14T10:00:00.000Z"}"""

    /**
     * Следующий человек на телефоне не должен видеть переписку прежнего — ни до ответа сервера,
     * ни после: пустая история нового треда оставляет ленту пустой, а не возвращает старую.
     */
    @Test
    fun `выход очищает ленту и проходит рукопожатие заново`() {
        val api = apiClient()
        val controller = started(messages = previousUserMessage, client = api)
        assertEquals(1, controller.state.value.messages.size)

        api.logout()
        controller.resetForIdentityChange()

        assertTrue(controller.state.value.messages.isEmpty())
        assertTrue(!controller.state.value.ready)

        server.enqueue(register())
        server.enqueue(history())
        await(controller) { it.ready }

        assertTrue(controller.state.value.messages.isEmpty())
        assertEquals("/api/v1/mobile/register", server.takeRequest().path)
        assertTrue(server.takeRequest().path!!.startsWith("/api/v1/mobile/messages"))
        val reRegister = server.takeRequest()
        assertEquals("/api/v1/mobile/register", reRegister.path)
        assertTrue(JSONObject(reRegister.body.readUtf8()).getBoolean("logout"))
    }

    @Test
    fun `приветствие хоста переживает смену пользователя`() {
        val controller = started(messages = previousUserMessage)
        controller.store.setGreeting("Чем помочь?")
        controller.setDraft("черновик прежнего")
        controller.stop()

        controller.resetForIdentityChange()

        val state = controller.state.value
        assertEquals("Чем помочь?", state.greeting)
        assertEquals("", state.draft)
        assertEquals(0L, state.lastServerMessageId)
    }

    @Test
    fun `смена пользователя при закрытом экране не ходит в сеть`() {
        val api = apiClient()
        val controller = started(messages = previousUserMessage, client = api)
        controller.stop()
        awaitControllerIdle()
        val afterStop = server.requestCount

        api.logout()
        controller.resetForIdentityChange()

        // Ни одной живой задачи сразу после вызова: рукопожатие, запусти его сброс, уже было бы
        // в scope. Пауза тут не нужна и ничего бы не доказала.
        assertTrue(scope.coroutineContext.job.children.none { it.isActive })
        assertEquals(afterStop, server.requestCount)
        assertTrue(controller.state.value.messages.isEmpty())
        assertTrue(!controller.state.value.ready)
    }

    /**
     * Догон ушёл до выхода, страница прежнего пользователя пришла после. Отмены задачи здесь
     * мало: `refresh()` запускает догон без хранимого `Job`. Порядок задан воротами сервера:
     * запрос долетел → сменился пользователь → ответ отпущен.
     */
    @Test
    fun `страница, запрошенная до смены пользователя, в ленту не попадает`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        controller.stop()
        awaitControllerIdle()
        dispatcher.clearArrivals()
        val gate = dispatcher.gateNextHistory(history(messages = previousUserMessage))

        controller.refresh()
        dispatcher.awaitHistory()
        controller.resetForIdentityChange()
        gate.countDown()
        awaitControllerIdle()

        assertTrue(controller.state.value.messages.isEmpty())
    }

    /**
     * Хост сменил токен дважды через публичный `ApiClient.setIdentityToken`, пока шла отправка:
     * поколение клиента сменилось, а эпоха контроллера — нет (её двигает только
     * `MeerBot.identify` через сброс ленты). Рукопожатие исчерпало попытки. Сообщение не ушло —
     * пользователь обязан это увидеть и повторить, а не потерять его молча. Через `MeerBot`
     * этот путь недостижим: там каждая смена identity, кроме свежего токена того же человека,
     * двигает и эпоху.
     */
    @Test
    fun `исчерпанное рукопожатие без смены пользователя помечает сообщение недоставленным`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val api = apiClient()
        val controller = controller(api)
        controller.start()
        await(controller) { it.ready }
        dispatcher.clearArrivals()
        api.setIdentityToken("token-2")
        val first = dispatcher.gateNextRegister()
        val second = dispatcher.gateNextRegister()

        controller.send("привет")
        dispatcher.awaitRegister()
        api.setIdentityToken("token-3")
        first.countDown()
        dispatcher.awaitRegister()
        api.setIdentityToken("token-4")
        second.countDown()

        await(controller) { it.retryable != null }
        val state = controller.state.value
        assertEquals("привет", state.retryable)
        assertEquals("cancelled", state.connectionError?.code)
        assertEquals(R.string.meerbot_err_cancelled, state.connectionError?.messageRes)
        assertTrue(state.messages.single { it.role == "user" }.failed)
        assertTrue(!state.sending)
    }

    // ─── Отправка до прихода стартовой истории ────────────────────────────────────────────

    /** Прошлый тред: стартовая история приносит его целиком. */
    private val earlierThread =
        """{"id":5,"role":"user","content":"старый вопрос","createdAt":"2026-08-14T10:00:00.000Z"},
           {"id":6,"role":"assistant","content":"старый ответ","createdAt":"2026-08-14T10:00:01.000Z"}"""

    /** Догон после отправки: сервер отдаёт то же сообщение и ответ уже со своими id. */
    private fun echoPage() =
        """{"id":7,"role":"user","content":"привет","createdAt":"${iso()}"},
           {"id":8,"role":"assistant","content":"Здравствуйте","createdAt":"${iso()}"}"""

    private val answerStream =
        "data: {\"choices\":[{\"delta\":{\"content\":\"Здравствуйте\"}}]}\n\ndata: [DONE]\n\n"

    /**
     * Человек открыл чат и сразу написал, а стартовая история ещё в пути. Порядок задан
     * воротами: история долетела до сервера → отправка ушла → история отпущена, пока сообщение
     * ещё отправляется. До правки история заменяла ленту целиком, и сообщение пропадало с экрана.
     */
    @Test
    fun `сообщение, отправленное до прихода истории, не пропадает и не двоится после эха`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val historyGate = dispatcher.gateNextHistory(ScriptedDispatcher.history(messages = earlierThread))
        val streamGate = dispatcher.gateNextStream(ScriptedDispatcher.sse(answerStream))
        val controller = controller()

        controller.start()
        dispatcher.awaitHistory()
        controller.send("привет")
        dispatcher.awaitStream()
        historyGate.countDown()
        await(controller) { it.ready }

        val applied = controller.state.value
        assertTrue(applied.sending)
        assertEquals(listOf("старый вопрос", "старый ответ", "привет", ""), applied.messages.map { it.content })
        val local = applied.messages[2]
        assertNull(local.serverId)
        assertTrue(applied.messages[3].streaming)

        dispatcher.historyFallback = { ScriptedDispatcher.history(messages = echoPage()) }
        streamGate.countDown()
        await(controller) { s -> !s.sending && s.messages.any { it.serverId == 8L } }

        val state = controller.state.value
        assertEquals(listOf("старый вопрос", "старый ответ", "привет", "Здравствуйте"), state.messages.map { it.content })
        assertEquals(listOf(5L, 6L, 7L, 8L), state.messages.map { it.serverId })
        assertEquals(local.id, state.messages[2].id)
        assertEquals(8L, state.lastServerMessageId)
    }

    /** Недоставленное сообщение с «Повторить» переживает стартовую историю и повтор не двоит его. */
    @Test
    fun `недоставленное до прихода истории сообщение остаётся с повтором`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val historyGate = dispatcher.gateNextHistory(ScriptedDispatcher.history(messages = earlierThread))
        dispatcher.streamFallback = { ScriptedDispatcher.error(500, "internal") }
        val controller = controller()

        controller.start()
        dispatcher.awaitHistory()
        controller.send("привет")
        await(controller) { it.retryable != null }
        historyGate.countDown()
        await(controller) { it.ready }

        val applied = controller.state.value
        assertEquals(listOf("старый вопрос", "старый ответ", "привет"), applied.messages.map { it.content })
        assertTrue(applied.messages.last().failed)
        assertNull(applied.messages.last().serverId)
        assertEquals("привет", applied.retryable)

        dispatcher.streamFallback = { ScriptedDispatcher.sse(answerStream) }
        dispatcher.historyFallback = { ScriptedDispatcher.history(messages = echoPage()) }
        controller.retry()
        await(controller) { s -> !s.sending && s.messages.any { it.serverId == 8L } }

        val state = controller.state.value
        assertEquals(listOf("старый вопрос", "старый ответ", "привет", "Здравствуйте"), state.messages.map { it.content })
        assertTrue(state.messages.none { it.failed })
        assertNull(state.retryable)
    }

    /**
     * Отправка дошла до сервера раньше, чем он собрал стартовую историю: эхо уже в ней. Эхо
     * промоутит своё сообщение, дубля нет, id пузыря прежний.
     */
    @Test
    fun `эхо сообщения в стартовой истории не двоит его`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val echoOnly = """{"id":7,"role":"user","content":"привет","createdAt":"${iso()}"}"""
        val historyGate = dispatcher.gateNextHistory(ScriptedDispatcher.history(messages = "$earlierThread, $echoOnly"))
        val streamGate = dispatcher.gateNextStream(ScriptedDispatcher.sse(answerStream))
        val controller = controller()

        controller.start()
        dispatcher.awaitHistory()
        controller.send("привет")
        dispatcher.awaitStream()
        val localId = controller.state.value.messages.single { it.role == "user" }.id
        historyGate.countDown()
        await(controller) { it.ready }

        val applied = controller.state.value
        assertEquals(listOf("старый вопрос", "старый ответ", "привет", ""), applied.messages.map { it.content })
        assertEquals(localId, applied.messages[2].id)
        assertEquals(7L, applied.messages[2].serverId)
        assertTrue(applied.messages[3].streaming)

        dispatcher.historyFallback = { ScriptedDispatcher.history(messages = echoPage()) }
        streamGate.countDown()
        await(controller) { s -> !s.sending && s.messages.any { it.serverId == 8L } }

        val state = controller.state.value
        assertEquals(listOf(5L, 6L, 7L, 8L), state.messages.map { it.serverId })
        assertEquals(localId, state.messages[2].id)
    }

    /** Вчерашний тред с тем же текстом, что отправляется сейчас. */
    private fun yesterdayThread() =
        """{"id":5,"role":"user","content":"да","createdAt":"${iso(System.currentTimeMillis() - DAY_MS)}"},
           {"id":6,"role":"assistant","content":"Хорошо","createdAt":"${iso(System.currentTimeMillis() - DAY_MS + 1_000)}"}"""

    /**
     * «Да» отправлено до прихода стартовой истории, в истории — вчерашнее «да» с ответом. До
     * правки вчерашняя строка забирала новое сообщение (и сама пропадала из ленты), а настоящее
     * эхо после ответа вставало вторым «да».
     */
    @Test
    fun `вчерашнее сообщение с тем же текстом — не эхо`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val historyGate = dispatcher.gateNextHistory(ScriptedDispatcher.history(messages = yesterdayThread()))
        val streamGate = dispatcher.gateNextStream(
            ScriptedDispatcher.sse("data: {\"choices\":[{\"delta\":{\"content\":\"Отлично\"}}]}\n\ndata: [DONE]\n\n"),
        )
        val controller = controller()

        controller.start()
        dispatcher.awaitHistory()
        controller.send("да")
        dispatcher.awaitStream()
        historyGate.countDown()
        await(controller) { it.ready }

        val applied = controller.state.value
        assertEquals(listOf("да", "Хорошо", "да", ""), applied.messages.map { it.content })
        assertEquals(listOf(5L, 6L, null, null), applied.messages.map { it.serverId })
        val localId = applied.messages[2].id

        val echo = """{"id":40,"role":"user","content":"да","createdAt":"${iso()}"},
                      {"id":41,"role":"assistant","content":"Отлично","createdAt":"${iso()}"}"""
        dispatcher.historyFallback = { ScriptedDispatcher.history(messages = "${yesterdayThread()}, $echo") }
        streamGate.countDown()
        await(controller) { s -> !s.sending && s.messages.any { it.serverId == 41L } }

        val state = controller.state.value
        assertEquals(listOf("да", "Хорошо", "да", "Отлично"), state.messages.map { it.content })
        assertEquals(listOf(5L, 6L, 40L, 41L), state.messages.map { it.serverId })
        assertEquals(localId, state.messages[2].id)
    }

    /**
     * Обрыв после открытия диалога, сообщение до сервера не дошло. История приносит вчерашнее
     * «да» и ответ после него — до правки это засчитывалось доставкой по id вчерашней строки:
     * «Повторить» не появлялся, и сообщение терялось молча.
     */
    @Test
    fun `обрыв со вчерашним сообщением того же текста оставляет «Повторить»`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val historyGate = dispatcher.gateNextHistory(ScriptedDispatcher.history(messages = yesterdayThread()))
        dispatcher.historyFallback = { ScriptedDispatcher.history(messages = yesterdayThread()) }
        dispatcher.streamFallback = { brokenStream(metaFrame) }
        val controller = controller()

        controller.start()
        dispatcher.awaitHistory()
        controller.send("да")
        await(controller) { it.retryable != null }
        historyGate.countDown()
        await(controller) { it.ready }

        val state = controller.state.value
        assertEquals("да", state.retryable)
        // Частично полученный ответ («нача») не выбрасывается — он остаётся под сообщением.
        assertEquals(listOf("да", "Хорошо", "да", "нача"), state.messages.map { it.content })
        assertEquals(listOf(5L, 6L, null, null), state.messages.map { it.serverId })
        assertTrue(state.messages[2].failed)
    }

    /**
     * Обрыв ДО того, как сервер записал сообщение, а история кончается прошлым ответом бота.
     * До правки это считалось «ответ дописан», лента заменялась, и сообщение пропадало без
     * «Повторить».
     */
    @Test
    fun `обрыв до записи сообщения не принимается за доставку`() {
        val api = apiClient()
        val controller = started(client = api)
        // Диалог уже открыт прошлым потоком — иначе история после обрыва не запрашивается.
        api.rememberConversationId(3)
        // Отказ шлюза, а не обрыв сокета: обрыв сразу после запроса OkHttp молча повторяет,
        // и повтор забрал бы из очереди следующий ответ — историю — как тело потока.
        server.enqueue(ScriptedDispatcher.error(502, "bad_gateway"))
        server.enqueue(history(messages = earlierThread))

        controller.send("привет")

        await(controller) { it.retryable != null }
        val state = controller.state.value
        assertEquals(listOf("старый вопрос", "старый ответ", "привет"), state.messages.map { it.content })
        assertTrue(state.messages.last().failed)
        assertEquals("привет", state.retryable)
    }

    /**
     * Плановый рестарт сервера посреди ответа, сервер ответ дописал. История вливается, а не
     * заменяет ленту, — недописанный пузырь не должен остаться рядом с серверной версией.
     */
    @Test
    fun `рестарт сервера посреди ответа не двоит ответ`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        dispatcher.historyFallback = { ScriptedDispatcher.history(messages = echoPage()) }
        dispatcher.streamFallback = {
            ScriptedDispatcher.sse(
                "data: {\"choices\":[{\"delta\":{\"content\":\"Здрав\"}}]}\n\n" +
                    "event: shutdown\ndata: {\"reason\":\"server_restart\"}\n\n",
            )
        }

        controller.send("привет")

        await(controller) { s ->
            !s.sending && s.messages.any { it.serverId == 8L } && s.messages.none { it.content == "Здрав" }
        }
        val state = controller.state.value
        assertEquals(listOf("привет", "Здравствуйте"), state.messages.map { it.content })
        assertEquals(listOf(7L, 8L), state.messages.map { it.serverId })
    }

    /**
     * Часы устройства спешат больше допуска, тред пуст, отправка оборвана, а сервер сообщение и
     * ответ записал. До правки эхо не узнавалось по времени: «Повторить» на доставленном
     * сообщении, серверная копия рядом и недописанный пузырь — повтор дал бы дубль и второй
     * платный ответ.
     */
    @Test
    fun `обрыв при спешащих часах и пустом треде засчитывается доставкой`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        val serverNow = System.currentTimeMillis() - 6 * 60 * 1000L
        dispatcher.historyFallback = {
            ScriptedDispatcher.history(
                messages = """{"id":7,"role":"user","content":"привет","createdAt":"${iso(serverNow)}"},
                   {"id":8,"role":"assistant","content":"Здравствуйте","createdAt":"${iso(serverNow)}"}""",
            )
        }
        dispatcher.streamFallback = { brokenStream(metaFrame) }

        controller.send("привет")

        await(controller) { s ->
            !s.sending && s.messages.any { it.serverId == 8L } && s.messages.none { it.content == "нача" }
        }
        val state = controller.state.value
        assertNull(state.retryable)
        assertEquals(listOf("привет", "Здравствуйте"), state.messages.map { it.content })
        assertEquals(listOf(7L, 8L), state.messages.map { it.serverId })
        assertTrue(state.messages.none { it.failed })
    }

    /**
     * Экран закрыли (`stop()`), пока сообщение отправлялось и эха ещё нет. Раньше отмена только
     * убирала пузырь: сообщение оставалось без пометки и без «Повторить». Паритет с iOS.
     */
    @Test
    fun `закрытие экрана во время отправки помечает сообщение недоставленным`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        val streamGate = dispatcher.gateNextStream(ScriptedDispatcher.sse(answerStream))
        controller.send("привет")
        dispatcher.awaitStream()

        controller.stop()

        await(controller) { it.retryable != null }
        val state = controller.state.value
        assertEquals("привет", state.retryable)
        assertEquals(listOf("привет"), state.messages.map { it.content })
        assertTrue(state.messages.single().failed)
        assertNull(state.connectionError)
        assertTrue(!state.sending)
        streamGate.countDown()
    }

    /** Эхо сообщения уже в ленте — оно доставлено: повтор отправил бы его второй раз. */
    @Test
    fun `закрытие экрана после эха сообщения повтор не предлагает`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val echoOnly = """{"id":7,"role":"user","content":"привет","createdAt":"${iso()}"}"""
        val historyGate = dispatcher.gateNextHistory(ScriptedDispatcher.history(messages = echoOnly))
        val streamGate = dispatcher.gateNextStream(ScriptedDispatcher.sse(answerStream))
        val controller = controller()
        controller.start()
        dispatcher.awaitHistory()
        controller.send("привет")
        dispatcher.awaitStream()
        historyGate.countDown()
        await(controller) { it.ready }
        assertEquals(7L, controller.state.value.messages.single { it.role == "user" }.serverId)

        controller.stop()

        await(controller) { s -> s.messages.none { it.role == "assistant" } }
        awaitControllerIdle()
        val state = controller.state.value
        assertNull(state.retryable)
        assertEquals(listOf(7L), state.messages.map { it.serverId })
        assertTrue(state.messages.none { it.failed })
        streamGate.countDown()
    }

    /** Смена пользователя по-прежнему уносит и то, что ещё отправляется. */
    @Test
    fun `смена пользователя убирает и неотправленное сообщение`() {
        val dispatcher = ScriptedDispatcher().also { server.dispatcher = it }
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        val streamGate = dispatcher.gateNextStream(ScriptedDispatcher.sse(answerStream))
        controller.send("мой номер заказа 123")
        dispatcher.awaitStream()

        controller.resetForIdentityChange()

        assertTrue(controller.state.value.messages.isEmpty())
        assertTrue(!controller.state.value.sending)
        streamGate.countDown()
        await(controller) { it.ready }
        assertTrue(controller.state.value.messages.isEmpty())
        // Отмена отправки прежнего пользователя «Повторить» новому не ставит.
        assertNull(controller.state.value.retryable)
    }

    @Test
    fun `heartbeat снимает баннер прошлой ошибки`() {
        val controller = started()
        server.enqueue(sse("event: heartbeat\ndata: {}\n\ndata: [DONE]\n\n"))
        controller.store.setError(ChatError("network_io", 0))

        controller.send("привет")

        await(controller) { it.connectionError == null && !it.sending }
    }
}

/**
 * Догон ленты — единственный надёжный канал «менеджер ответил → пользователь увидел»: поток
 * живёт только на время ответа бота, а пуш зависит от бэкенда интегратора.
 *
 * Тесты ужимают периоды до миллисекунд: проверять шестисекундный тик ожиданием шести секунд
 * — верный способ получить мигающий набор в релизном скрипте. Паузы наугад не используются:
 * догон последовательный, поэтому приход (N+1)-го запроса истории означает, что N-я страница
 * уже влита.
 */
class ChatControllerCatchUpTest {

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ChatController.managerPollIntervalMs = 40L
        ChatController.idlePollIntervalMs = 40L
    }

    @After
    fun tearDown() {
        ChatController.managerPollIntervalMs = 6_000L
        ChatController.idlePollIntervalMs = 12_000L
        scope.cancel()
        server.shutdown()
    }

    private fun controller(): ChatController = ChatController(
        client = ApiClient(
            config = MeerBotConfiguration(
                apiKey = "pk_live_mobile",
                baseUrl = server.url("/").toString().trimEnd('/'),
                sdkVersion = "0.2.4-test",
            ),
            visitorUuid = "11111111-1111-1111-1111-111111111111",
            installationId = "and-22222222-2222-2222-2222-222222222222",
        ),
        scope = scope,
    )

    private fun register() = MockResponse().setBody(
        """{"deviceId":"42","jwt":"jwt-1","expiresIn":900,"attestationRequired":false,"identity":{"status":"not_provided"}}"""
    )

    private fun history(mode: String = "ai", messages: String = "") = MockResponse().setBody(
        """{"messages":[$messages],"hasMore":false,"mode":"$mode"}"""
    )

    private fun managerMessage(id: Int, text: String) =
        """{"id":$id,"role":"assistant","content":"$text","authorKind":"manager","authorName":"Роман","createdAt":"2026-08-25T10:00:00.000Z"}"""

    private fun await(controller: ChatController, timeoutMs: Long = 5_000, check: (ChatState) -> Boolean) =
        awaitState(controller, timeoutMs, check)

    private fun started(mode: String = "human"): ChatController {
        server.enqueue(register())
        server.enqueue(history(mode = mode))
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        return controller
    }

    /** Старт через диспетчер: ответы по пути, приходы запросов считаются. */
    private fun startedScripted(dispatcher: ScriptedDispatcher): ChatController {
        server.dispatcher = dispatcher
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        return controller
    }

    @Test
    fun `ответ менеджера доезжает без единой отправки`() {
        val controller = started()
        server.enqueue(history(mode = "human", messages = managerMessage(9, "я тут")))

        await(controller) { it.messages.any { m -> m.content == "я тут" } }

        assertEquals("manager", controller.state.value.messages.last().author)
    }

    @Test
    fun `догон несёт курсор since, а не тянет ленту целиком`() {
        val controller = started()
        server.enqueue(history(mode = "human", messages = managerMessage(9, "первое")))
        await(controller) { it.messages.isNotEmpty() }
        server.enqueue(history(mode = "human", messages = managerMessage(10, "второе")))
        await(controller) { it.messages.size == 2 }

        server.takeRequest() // register
        server.takeRequest() // стартовая история
        val firstCatchUp = server.takeRequest().requestUrl!!
        val secondCatchUp = server.takeRequest().requestUrl!!

        assertNull(firstCatchUp.queryParameter("since"))
        assertEquals("9", secondCatchUp.queryParameter("since"))
    }

    @Test
    fun `повторная страница не дублирует сообщение`() {
        val dispatcher = ScriptedDispatcher()
        dispatcher.historyFallback = { ScriptedDispatcher.history(mode = "human", messages = managerMessage(9, "я тут")) }
        val controller = startedScripted(dispatcher)
        dispatcher.clearArrivals()

        // Четыре прихода после старта — не меньше трёх влитых повторов той же страницы.
        repeat(4) { dispatcher.awaitHistory() }

        assertEquals(1, controller.state.value.messages.size)
    }

    /** Оборванная сеть у того, кто просто смотрит переписку, — не повод красить экран. */
    @Test
    fun `ошибка фонового догона не показывается пользователю`() {
        val dispatcher = ScriptedDispatcher()
        val controller = startedScripted(dispatcher)
        dispatcher.historyFallback = { ScriptedDispatcher.error(500, "internal") }
        dispatcher.clearArrivals()

        repeat(3) { dispatcher.awaitHistory() }

        assertNull(controller.state.value.connectionError)
    }

    /**
     * Гарантия здесь — «после stop() НОВЫЕ циклы догона не начинаются», а не «сеть замирает в
     * ту же наносекунду»: запрос, отправленный до stop(), долетает. Поэтому сначала все задачи
     * доводятся до конца, и уже тогда проверяется, что живых задач — а значит и новых запросов —
     * нет.
     */
    @Test
    fun `stop останавливает догон`() {
        val dispatcher = ScriptedDispatcher()
        dispatcher.historyFallback = { ScriptedDispatcher.history(mode = "human") }
        val controller = startedScripted(dispatcher)
        dispatcher.clearArrivals()
        repeat(2) { dispatcher.awaitHistory() }

        controller.stop()
        awaitIdle(scope)
        val afterStop = server.requestCount

        assertTrue(scope.coroutineContext.job.children.none { it.isActive })
        assertEquals(afterStop, server.requestCount)
    }

    /**
     * Сервер не признаёт устройство и после переподключения. Без остановки каждый тик — это
     * рукопожатие и две истории, вечно и молча. Опрос встаёт (в scope не остаётся задач — новых
     * запросов быть не может), экран говорит правду, повторное открытие возвращает догон.
     */
    @Test
    fun `непризнанное после переподключения устройство останавливает фоновый догон`() {
        val dispatcher = ScriptedDispatcher()
        val controller = startedScripted(dispatcher)

        dispatcher.historyFallback = { ScriptedDispatcher.error(401, "device_not_found") }
        await(controller) { it.connectionError?.code == "device_not_found" }
        awaitIdle(scope)

        assertEquals(R.string.meerbot_err_session_lost, controller.state.value.connectionError?.messageRes)

        dispatcher.historyFallback = { ScriptedDispatcher.history(mode = "human") }
        controller.start()
        await(controller) { it.connectionError == null }
        assertTrue(scope.coroutineContext.job.children.any { it.isActive })
    }

    /** Возврат из фона — не действие пользователя в чате: остановленный догон он не будит. */
    @Test
    fun `возврат из фона не будит остановленный догон`() {
        val dispatcher = ScriptedDispatcher()
        val controller = startedScripted(dispatcher)
        dispatcher.historyFallback = { ScriptedDispatcher.error(401, "device_not_found") }
        await(controller) { it.connectionError?.code == "device_not_found" }
        awaitIdle(scope)
        val afterSuspend = server.requestCount

        controller.onEnterForeground()

        assertTrue(scope.coroutineContext.job.children.none { it.isActive })
        assertEquals(afterSuspend, server.requestCount)
    }

    /** Экран закрыт — фоновый возврат приложения не имеет права поднимать опрос. */
    @Test
    fun `возврат из фона при закрытом экране ничего не делает`() {
        val controller = started()
        controller.stop()
        awaitIdle(scope)
        val afterStop = server.requestCount

        controller.onEnterForeground()

        assertTrue(scope.coroutineContext.job.children.none { it.isActive })
        assertEquals(afterStop, server.requestCount)
    }
}
