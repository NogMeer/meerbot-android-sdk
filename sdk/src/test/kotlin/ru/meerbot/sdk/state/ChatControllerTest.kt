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
                apiKey = "pk_live_widget",
                baseUrl = server.url("/").toString().trimEnd('/'),
                origin = "https://ru.meerbot.demo",
                sdkVersion = "0.1.0-test",
            ),
            visitorUuid = "11111111-1111-1111-1111-111111111111",
        ),
        scope = scope,
    )

    private fun session(conversationId: Long? = null, greeting: String? = null, mode: String = "ai") =
        MockResponse().setResponseCode(201).setBody(
            buildString {
                append("""{"jwt":"jwt-1","expiresIn":900,"mode":"$mode"""")
                if (conversationId != null) append(""","conversationId":$conversationId""")
                append(""","widget":{"title":"Поддержка"""")
                if (greeting != null) append(""","greeting":"$greeting"""")
                append("}}")
            }
        )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun await(controller: ChatController, timeoutMs: Long = 5_000, check: (ChatState) -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (check(controller.state.value)) return
            Thread.sleep(20)
        }
        fail("состояние не дождалось условия: ${controller.state.value}")
    }

    @Test
    fun `приветствие канала приходит из handshake`() {
        server.enqueue(session(greeting = "Чем помочь?"))
        val controller = controller()

        controller.start()

        await(controller) { it.ready }
        assertEquals("Чем помочь?", controller.state.value.greeting)
    }

    @Test
    fun `повторный старт не делает второй handshake`() {
        server.enqueue(session())
        val controller = controller()

        controller.start()
        await(controller) { it.ready }
        controller.start()
        Thread.sleep(200)

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `ответ стримится в ленту и снимает признак отправки`() {
        server.enqueue(session())
        server.enqueue(
            sse(
                "event: meta\ndata: {\"conversationId\":3,\"mode\":\"ai\"}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Здрав\"}}]}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"ствуйте\"}}]}\n\n" +
                    "data: [DONE]\n\n"
            )
        )
        val controller = controller()
        controller.start()
        await(controller) { it.ready }

        controller.send("привет")

        await(controller) { !it.sending && it.messages.size == 2 }
        val state = controller.state.value
        assertEquals("привет", state.messages[0].content)
        assertEquals("Здравствуйте", state.messages[1].content)
        assertTrue(!state.messages[1].streaming)
        assertNull(state.connectionError)
    }

    @Test
    fun `обрыв помечает сообщение недоставленным и разрешает повтор`() {
        server.enqueue(session())
        // Без meta: диалога ещё нет, значит и догонять историю нечего — виден чистый путь отказа.
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"нача\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        val controller = controller()
        controller.start()
        await(controller) { it.ready }

        controller.send("привет")

        await(controller) { it.connectionError != null }
        val state = controller.state.value
        assertEquals("привет", state.retryable)
        assertTrue(state.messages.first { it.role == "user" }.failed)
        assertTrue(!state.sending)
    }

    @Test
    fun `повтор доводит ответ и снимает пометку`() {
        server.enqueue(session())
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"готово\"}}]}\n\ndata: [DONE]\n\n"))
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
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
        server.enqueue(session())
        server.enqueue(
            // MockWebServer рвёт соединение на середине тела, поэтому meta должна успеть
            // уехать целиком: добиваем поток keep-alive-комментариями.
            sse(
                "event: meta\ndata: {\"conversationId\":8,\"mode\":\"ai\"}\n\n" +
                    ": keep-alive\n".repeat(40)
            ).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[
                   {"id":1,"role":"user","content":"привет","createdAt":"2026-08-14T10:00:00.000Z"},
                   {"id":2,"role":"assistant","content":"ответ дописан","createdAt":"2026-08-14T10:00:02.000Z"}
                ]}"""
            )
        )
        val controller = controller()
        controller.start()
        await(controller) { it.ready }

        controller.send("привет")

        await(controller) { it.messages.any { m -> m.content == "ответ дописан" } }
        val state = controller.state.value
        assertEquals(2, state.messages.size)
        // Лента заменена серверной — недоставленных пометок не остаётся.
        assertTrue(state.messages.none { it.failed })
    }

    @Test
    fun `в закрытом диалоге отправка не уходит`() {
        server.enqueue(session(mode = "closed"))
        val controller = controller()
        controller.start()
        await(controller) { it.ready }

        controller.send("привет")
        Thread.sleep(200)

        assertEquals(ChatMode.Closed, controller.state.value.mode)
        assertTrue(controller.state.value.messages.isEmpty())
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `пуш открывает диалог и подтягивает историю`() {
        server.enqueue(session())
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[
                   {"id":5,"role":"assistant","content":"вам ответил менеджер","createdAt":"2026-08-14T10:00:00.000Z"}
                ]}"""
            )
        )
        val controller = controller()
        controller.start()
        await(controller) { it.ready }

        controller.openConversation(42)

        await(controller) { it.messages.any { m -> m.content == "вам ответил менеджер" } }
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("conversationId=42"))
    }

    @Test
    fun `heartbeat снимает баннер прошлой ошибки`() {
        server.enqueue(session())
        server.enqueue(
            sse("event: heartbeat\ndata: {}\n\ndata: [DONE]\n\n")
        )
        val controller = controller()
        controller.start()
        await(controller) { it.ready }
        controller.store.setError(ChatError("network_io", 0))

        controller.send("привет")

        await(controller) { it.connectionError == null && !it.sending }
    }
}
