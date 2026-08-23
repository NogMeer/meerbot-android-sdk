package ru.meerbot.sdk.state

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.MeerBotConfiguration

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
        scope.cancel()
        server.shutdown()
    }

    private fun controller(): ChatController = ChatController(
        client = ApiClient(
            config = MeerBotConfiguration(
                apiKey = "pk_live_mobile",
                baseUrl = server.url("/").toString().trimEnd('/'),
                sdkVersion = "0.2.0-test",
            ),
            visitorUuid = "11111111-1111-1111-1111-111111111111",
            installationId = "and-22222222-2222-2222-2222-222222222222",
        ),
        scope = scope,
    )

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

    /** Стартовать и дождаться готовности: рукопожатие + пустая история. */
    private fun started(mode: String = "ai", messages: String = ""): ChatController {
        server.enqueue(register())
        server.enqueue(history(mode = mode, messages = messages))
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        return controller
    }

    private fun await(controller: ChatController, timeoutMs: Long = 5_000, check: (ChatState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check(controller.state.value)) return
            Thread.sleep(20)
        }
        fail("состояние не дождалось условия: ${controller.state.value}")
    }

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

    @Test
    fun `повторный старт не делает второе рукопожатие`() {
        val controller = started()

        controller.start()
        Thread.sleep(200)

        assertEquals(2, server.requestCount)
    }

    @Test
    fun `ответ стримится в ленту и снимает признак отправки`() {
        val controller = started()
        server.enqueue(
            sse(
                "event: meta\ndata: {\"conversationId\":3,\"mode\":\"ai\"}\n\n" +
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
        // Серверная лента не заканчивается ответом — значит, ответа не было, и сообщение
        // пользователя честно помечается недоставленным.
        server.enqueue(history())

        controller.send("привет")

        await(controller) { it.connectionError != null }
        val state = controller.state.value
        assertEquals("привет", state.retryable)
        assertTrue(state.messages.first { it.role == "user" }.failed)
        assertTrue(!state.sending)
    }

    @Test
    fun `повтор доводит ответ и снимает пометку`() {
        val controller = started()
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.enqueue(history())
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

    @Test
    fun `серверная история заменяет ленту после обрыва`() {
        val controller = started()
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"нача\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        // Сервер успел дописать ответ, пока рвалось соединение.
        server.enqueue(
            history(
                messages = """{"id":1,"role":"user","content":"привет","createdAt":"2026-08-14T10:00:00.000Z"},
                   {"id":2,"role":"assistant","content":"ответ дописан","createdAt":"2026-08-14T10:00:02.000Z"}"""
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

    @Test
    fun `в закрытом диалоге отправка не уходит`() {
        val controller = started(mode = "closed")

        controller.send("привет")
        Thread.sleep(200)

        assertEquals(ChatMode.Closed, controller.state.value.mode)
        assertTrue(controller.state.value.messages.isEmpty())
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

    @Test
    fun `heartbeat снимает баннер прошлой ошибки`() {
        val controller = started()
        server.enqueue(sse("event: heartbeat\ndata: {}\n\ndata: [DONE]\n\n"))
        controller.store.setError(ChatError("network_io", 0))

        controller.send("привет")

        await(controller) { it.connectionError == null && !it.sending }
    }
}
