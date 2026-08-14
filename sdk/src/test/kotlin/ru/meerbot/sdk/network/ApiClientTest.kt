package ru.meerbot.sdk.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.meerbot.sdk.R

/**
 * Сетевой слой против MockWebServer. Проверяем форму запросов (её ломали дважды), поведение
 * с токеном и поведение при обрыве — то есть ровно то, что нельзя увидеть глазами в приложении.
 */
class ApiClientTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun client(pushApiKey: String? = null) = ApiClient(
        config = MeerBotConfiguration(
            apiKey = "pk_live_widget",
            pushApiKey = pushApiKey,
            baseUrl = server.url("/").toString().trimEnd('/'),
            origin = "https://ru.meerbot.demo",
            sdkVersion = "0.1.0-test",
        ),
        visitorUuid = VISITOR,
    )

    private fun sessionResponse(
        jwt: String = "jwt-1",
        expiresIn: Int = 900,
        conversationId: Long? = null,
        greeting: String? = null,
        mode: String = "ai",
    ): MockResponse {
        val body = JSONObject()
            .put("jwt", jwt)
            .put("expiresIn", expiresIn)
            .put("mode", mode)
        if (conversationId != null) body.put("conversationId", conversationId)
        body.put(
            "widget",
            JSONObject().put("title", "Поддержка").apply {
                if (greeting != null) put("greeting", greeting)
            },
        )
        return MockResponse().setResponseCode(201).setBody(body.toString())
    }

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun errorResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setBody("""{"error":{"type":"invalid_request","code":"$code","message":"nope"}}""")

    // ─── Handshake ────────────────────────────────────────────────────────────────────────

    @Test
    fun `handshake шлёт ключ, визитора и hostOrigin`() = runBlocking {
        server.enqueue(sessionResponse(greeting = "Привет!", conversationId = 5))

        val session = client().openSession()

        val request = server.takeRequest()
        assertEquals("/api/v1/widget/session", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("pk_live_widget", body.getString("key"))
        assertEquals(VISITOR, body.getString("visitorUuid"))
        assertEquals("https://ru.meerbot.demo", body.getString("hostOrigin"))
        // Легаси-поле externalUserId сервер игнорирует — слать его значит тихо терять identity.
        assertTrue(!body.has("externalUserId"))

        assertEquals("https://ru.meerbot.demo", request.getHeader("Origin"))
        assertEquals("0.1.0-test", request.getHeader("X-SDK-Version"))

        assertEquals("Привет!", session.greeting)
        assertEquals(5L, session.conversationId)
    }

    @Test
    fun `ошибка ключа отдаёт машинный код`() = runBlocking {
        server.enqueue(errorResponse(401, "key_invalid"))

        val error = runCatching { client().openSession() }.exceptionOrNull()

        assertTrue(error is MeerBotError.Http)
        assertEquals("key_invalid", (error as MeerBotError).code)
        assertEquals(R.string.meerbot_err_key_invalid, error.messageRes)
    }

    @Test
    fun `действующий токен переиспользуется`() = runBlocking {
        server.enqueue(sessionResponse())
        val api = client()

        assertEquals("jwt-1", api.validToken())
        assertEquals("jwt-1", api.validToken())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `параллельные запросы делают один handshake`() = runBlocking {
        server.enqueue(sessionResponse())
        val api = client()

        val tokens = withContext(Dispatchers.Default) {
            (1..8).map { async { api.validToken() } }.awaitAll()
        }

        assertEquals(List(8) { "jwt-1" }, tokens)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `истекающий токен обновляется`() = runBlocking {
        // expiresIn меньше минуты — переиспользовать такой токен нельзя, он умрёт в полёте.
        server.enqueue(sessionResponse(jwt = "jwt-short", expiresIn = 30))
        server.enqueue(sessionResponse(jwt = "jwt-fresh"))
        val api = client()

        assertEquals("jwt-short", api.openSession().jwt)
        assertEquals("jwt-fresh", api.validToken())
        assertEquals(2, server.requestCount)
    }

    // ─── Чат ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `тело чата содержит message, а не content`() = runBlocking {
        server.enqueue(sessionResponse())
        server.enqueue(sse("event: meta\ndata: {\"conversationId\":77,\"mode\":\"ai\"}\n\ndata: [DONE]\n\n"))
        val api = client()

        api.sendMessage("привет").toList()

        server.takeRequest() // handshake
        val request = server.takeRequest()
        assertEquals("/api/v1/widget/chat/stream", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("привет", body.getString("message"))
        assertTrue(!body.has("content"))
        assertEquals("Bearer jwt-1", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
    }

    @Test
    fun `диалог из meta уходит в следующий запрос`() = runBlocking {
        server.enqueue(sessionResponse())
        server.enqueue(sse("event: meta\ndata: {\"conversationId\":77,\"mode\":\"ai\"}\n\ndata: [DONE]\n\n"))
        server.enqueue(sse("data: [DONE]\n\n"))
        val api = client()

        api.sendMessage("первое").toList()
        assertEquals(77L, api.conversationId)

        api.sendMessage("второе").toList()
        server.takeRequest()
        server.takeRequest()
        val second = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals(77L, second.getLong("conversationId"))
    }

    @Test
    fun `ответ собирается из множества чанков`() = runBlocking {
        server.enqueue(sessionResponse())
        server.enqueue(
            sse(
                buildString {
                    append("event: meta\ndata: {\"conversationId\":1,\"mode\":\"ai\"}\n\n")
                    listOf("Здрав", "ствуй", "те!").forEach {
                        append("data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n")
                    }
                    append("data: [DONE]\n\n")
                }
            )
        )

        val events = client().sendMessage("привет").toList()

        val text = events.filterIsInstance<ChatStreamEvent.ContentDelta>()
            .joinToString("") { it.text }
        assertEquals("Здравствуйте!", text)
        assertTrue(events.last() is ChatStreamEvent.Done)
    }

    @Test
    fun `протухший токен обновляется и запрос повторяется один раз`() = runBlocking {
        server.enqueue(sessionResponse(jwt = "jwt-old"))
        server.enqueue(errorResponse(401, "jwt_expired"))
        server.enqueue(sessionResponse(jwt = "jwt-new"))
        server.enqueue(sse("data: {\"choices\":[{\"delta\":{\"content\":\"ок\"}}]}\n\ndata: [DONE]\n\n"))

        val events = client().sendMessage("привет").toList()

        assertTrue(events.any { it is ChatStreamEvent.ContentDelta })
        assertEquals(4, server.requestCount)
        server.takeRequest()
        server.takeRequest()
        server.takeRequest()
        assertEquals("Bearer jwt-new", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `повтор не зацикливается`() = runBlocking {
        server.enqueue(sessionResponse(jwt = "jwt-old"))
        server.enqueue(errorResponse(401, "jwt_expired"))
        server.enqueue(sessionResponse(jwt = "jwt-new"))
        server.enqueue(errorResponse(401, "jwt_expired"))

        val error = runCatching { client().sendMessage("привет").toList() }.exceptionOrNull()

        assertTrue(error is MeerBotError.Http)
        // handshake + стрим + handshake + стрим, и ни одного лишнего.
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `перепутанный канал не лечится повтором`() = runBlocking {
        // 401 jwt_channel_mismatch приходит, когда чат идёт по ключу мобильного приложения.
        // Повтор здесь бессмысленен: новый токен будет ровно таким же.
        server.enqueue(sessionResponse())
        server.enqueue(errorResponse(401, "jwt_channel_mismatch"))

        val error = runCatching { client().sendMessage("привет").toList() }.exceptionOrNull()

        assertEquals("jwt_channel_mismatch", (error as MeerBotError).code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `обрыв посреди потока не теряет полученное`() = runBlocking {
        server.enqueue(sessionResponse())
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"нача\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        val received = mutableListOf<ChatStreamEvent>()
        val error = runCatching {
            client().sendMessage("привет").collect { received += it }
        }.exceptionOrNull()

        assertTrue("ожидали сетевую ошибку, получили $error", error is MeerBotError.Network)
        assertTrue(received.isEmpty() || received.first() is ChatStreamEvent.ContentDelta)
    }

    @Test
    fun `ошибка в потоке приходит событием, а не исключением`() = runBlocking {
        server.enqueue(sessionResponse())
        server.enqueue(sse("event: error\ndata: {\"code\":\"ai_unavailable\",\"message\":\"нет\"}\n\n"))

        val events = client().sendMessage("привет").toList()

        assertEquals(
            ChatStreamEvent.ServerError("ai_unavailable", "нет"),
            events.single(),
        )
    }

    // ─── История ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `история догоняется и запоминает последнее сообщение`() = runBlocking {
        server.enqueue(sessionResponse(conversationId = 12))
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[
                   {"id":1,"role":"user","content":"привет","createdAt":"2026-08-14T10:00:00.000Z"},
                   {"id":2,"role":"assistant","content":"здравствуйте","createdAt":"2026-08-14T10:00:01.000Z"}
                ]}"""
            )
        )
        val api = client()
        api.openSession()

        val messages = api.history()

        assertEquals(2, messages.size)
        assertEquals("здравствуйте", messages[1].content)
        assertEquals(2L, api.lastMessageId)

        server.takeRequest()
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/v1/widget/messages?"))
        assertTrue(request.path!!.contains("conversationId=12"))
        assertEquals("Bearer jwt-1", request.getHeader("Authorization"))
    }

    @Test
    fun `без диалога история не запрашивается`() = runBlocking {
        assertTrue(client().history().isEmpty())
        assertEquals(0, server.requestCount)
    }

    // ─── Регистрация устройства ───────────────────────────────────────────────────────────

    @Test
    fun `регистрация устройства идёт по ключу приложения`() = runBlocking {
        server.enqueue(
            MockResponse().setBody(
                """{"deviceId":"dev-1","jwt":"mobile-jwt","expiresIn":900,"attestationRequired":false}"""
            )
        )

        val registration = client(pushApiKey = "pk_live_mobile").registerDevice("fcm-token")

        assertEquals("dev-1", registration.deviceId)
        val request = server.takeRequest()
        assertEquals("/api/v1/mobile/register", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("pk_live_mobile", body.getString("key"))
        assertEquals("android", body.getString("platform"))
        assertEquals("fcm-token", body.getString("deviceToken"))
        assertEquals("0.1.0-test", body.getString("sdkVersion"))
    }

    @Test
    fun `без ключа приложения регистрация не уходит в сеть`() = runBlocking {
        val error = runCatching { client().registerDevice("fcm-token") }.exceptionOrNull()

        assertTrue(error is MeerBotError.NotConfigured)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `мобильный JWT не используется для чата`() = runBlocking {
        server.enqueue(
            MockResponse().setBody("""{"deviceId":"dev-1","jwt":"mobile-jwt","expiresIn":900}""")
        )
        server.enqueue(sessionResponse(jwt = "widget-jwt"))
        server.enqueue(sse("data: [DONE]\n\n"))
        val api = client(pushApiKey = "pk_live_mobile")

        api.registerDevice("fcm-token")
        api.sendMessage("привет").toList()

        server.takeRequest() // register
        server.takeRequest() // handshake — значит, mobile-jwt не приняли за рабочий токен
        assertEquals("Bearer widget-jwt", server.takeRequest().getHeader("Authorization"))
    }

    // ─── Разбор служебного ────────────────────────────────────────────────────────────────

    @Test
    fun `неизвестная форма ошибки не теряет статус`() {
        val error = ApiClient.decodeError(503, "<html>gateway</html>")
        assertEquals("http_503", error.code)
        assertEquals(503, error.status)
    }

    @Test
    fun `метка времени разбирается с миллисекундами и без`() {
        assertEquals(
            ApiClient.parseTimestamp("2026-08-14T10:00:00.000Z"),
            ApiClient.parseTimestamp("2026-08-14T10:00:00Z"),
        )
    }

    @Test
    fun `пустой ответ handshake — некорректный ответ`() = runBlocking {
        server.enqueue(MockResponse().setBody("{}"))

        val error = runCatching { client().openSession() }.exceptionOrNull()

        assertTrue(error is MeerBotError.InvalidResponse)
        assertNull(client().conversationId)
    }

    @Test
    fun `нет сети — сетевая ошибка, а не падение`() = runBlocking {
        server.shutdown()

        val error = runCatching { client().openSession() }.exceptionOrNull()

        if (error !is MeerBotError.Network) fail("ожидали Network, получили $error")
        assertEquals("network_io", (error as MeerBotError).code)
    }

    private companion object {
        const val VISITOR = "11111111-1111-1111-1111-111111111111"
    }
}
