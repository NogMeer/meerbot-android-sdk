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
import ru.meerbot.sdk.state.ChatMode

/**
 * Сетевой слой канала `mobile_app` против MockWebServer.
 *
 * Проверяем форму запросов (её ломали дважды — сперва полем `content`, потом чужим каналом),
 * поведение с токеном и поведение при обрыве: то, чего не видно глазами в приложении.
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

    private fun client() = ApiClient(
        config = MeerBotConfiguration(
            apiKey = "pk_live_mobile",
            baseUrl = server.url("/").toString().trimEnd('/'),
            sdkVersion = "0.2.0-test",
        ),
        visitorUuid = VISITOR,
        installationId = INSTALLATION,
    )

    private fun registerResponse(
        jwt: String = "jwt-1",
        expiresIn: Int = 900,
        identityStatus: String = "not_provided",
        attestationRequired: Boolean = false,
    ) = MockResponse().setBody(
        JSONObject()
            .put("deviceId", "42")
            .put("jwt", jwt)
            .put("expiresIn", expiresIn)
            .put("attestationRequired", attestationRequired)
            .put("identity", JSONObject().put("status", identityStatus))
            .toString()
    )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    /** Отказы канала приходят без поля `type`, в отличие от веб-виджета. */
    private fun errorResponse(status: Int, code: String) = MockResponse()
        .setResponseCode(status)
        .setBody("""{"error":{"code":"$code","message":"nope"}}""")

    // ─── Рукопожатие ──────────────────────────────────────────────────────────────────────

    @Test
    fun `рукопожатие идёт в канал приложения, а не в виджет`() = runBlocking {
        server.enqueue(registerResponse())

        val session = client().openSession()

        val request = server.takeRequest()
        assertEquals("/api/v1/mobile/register", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("pk_live_mobile", body.getString("key"))
        assertEquals("android", body.getString("platform"))
        assertEquals(VISITOR, body.getString("visitorUuid"))
        assertEquals(INSTALLATION, body.getString("deviceToken"))
        assertEquals("0.2.0-test", request.getHeader("X-SDK-Version"))
        // Origin у нативного приложения нет: выдумывать его, как делал виджетный клиент,
        // больше не нужно.
        assertNull(request.getHeader("Origin"))

        assertEquals("42", session.deviceId)
        assertEquals(IdentityStatus.NotProvided, session.identityStatus)
    }

    @Test
    fun `токен идентичности уходит в рукопожатие и статус возвращается`() = runBlocking {
        server.enqueue(registerResponse(identityStatus = "verified"))
        val api = client()

        api.setIdentityToken("signed.jwt.here")
        val session = api.openSession()

        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertEquals("signed.jwt.here", body.getString("identityToken"))
        assertEquals(IdentityStatus.Verified, session.identityStatus)
        assertEquals(IdentityStatus.Verified, api.identityStatus)
    }

    @Test
    fun `отклонённая идентичность не роняет сессию`() = runBlocking {
        // Сервер отвечает 200 и анонимной сессией: чат обязан работать, даже если у
        // интегратора протух секрет. Клиент должен уметь это показать, а не молчать.
        server.enqueue(registerResponse(identityStatus = "rejected"))
        val api = client()

        api.setIdentityToken("bad")
        api.openSession()

        assertEquals(IdentityStatus.Rejected, api.identityStatus)
    }

    @Test
    fun `смена токена идентичности сбрасывает текущую сессию`() = runBlocking {
        server.enqueue(registerResponse(jwt = "jwt-anon"))
        server.enqueue(registerResponse(jwt = "jwt-identified", identityStatus = "verified"))
        val api = client()

        assertEquals("jwt-anon", api.validToken())
        api.setIdentityToken("signed")
        // Ждать 15 минут до применения identity нельзя — токен обязан обновиться сразу.
        assertEquals("jwt-identified", api.validToken())
        assertEquals(2, server.requestCount)
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
        server.enqueue(registerResponse())
        val api = client()

        assertEquals("jwt-1", api.validToken())
        assertEquals("jwt-1", api.validToken())

        assertEquals(1, server.requestCount)
    }

    @Test
    fun `параллельные запросы делают одно рукопожатие`() = runBlocking {
        server.enqueue(registerResponse())
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
        server.enqueue(registerResponse(jwt = "jwt-short", expiresIn = 30))
        server.enqueue(registerResponse(jwt = "jwt-fresh"))
        val api = client()

        assertEquals("jwt-short", api.openSession().jwt)
        assertEquals("jwt-fresh", api.validToken())
        assertEquals(2, server.requestCount)
    }

    // ─── Чат ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `чат идёт по своему роуту и не выбирает диалог`() = runBlocking {
        server.enqueue(registerResponse())
        server.enqueue(sse("event: meta\ndata: {\"conversationId\":77,\"mode\":\"ai\"}\n\ndata: [DONE]\n\n"))
        val api = client()

        api.sendMessage("привет").toList()

        server.takeRequest() // рукопожатие
        val request = server.takeRequest()
        assertEquals("/api/v1/mobile/chat/stream", request.path)
        val body = JSONObject(request.body.readUtf8())
        assertEquals("привет", body.getString("message"))
        // Диалог резолвит сервер по устройству из токена: id в теле означал бы, что клиент
        // выбирает, в чей тред писать.
        assertTrue(!body.has("conversationId"))
        assertEquals("Bearer jwt-1", request.getHeader("Authorization"))
        assertEquals("text/event-stream", request.getHeader("Accept"))
        // Значение из meta наружу отдаём — хосту нужно гасить свой пуш об открытом диалоге.
        assertEquals(77L, api.conversationId)
    }

    @Test
    fun `ответ собирается из множества чанков`() = runBlocking {
        server.enqueue(registerResponse())
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
        server.enqueue(registerResponse(jwt = "jwt-old"))
        server.enqueue(errorResponse(401, "jwt_expired"))
        server.enqueue(registerResponse(jwt = "jwt-new"))
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
        server.enqueue(registerResponse(jwt = "jwt-old"))
        server.enqueue(errorResponse(401, "jwt_expired"))
        server.enqueue(registerResponse(jwt = "jwt-new"))
        server.enqueue(errorResponse(401, "jwt_expired"))

        val error = runCatching { client().sendMessage("привет").toList() }.exceptionOrNull()

        assertTrue(error is MeerBotError.Http)
        assertEquals(4, server.requestCount)
    }

    @Test
    fun `чужой канал не лечится повтором`() = runBlocking {
        // 403 channel_mismatch = в конфигурации ключ другого канала. Новый токен будет ровно
        // таким же, поэтому повтор запрещён — и сервер поэтому отдаёт 403, а не 401.
        server.enqueue(registerResponse())
        server.enqueue(errorResponse(403, "channel_mismatch"))

        val error = runCatching { client().sendMessage("привет").toList() }.exceptionOrNull()

        assertEquals("channel_mismatch", (error as MeerBotError).code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `отказ допуска не выглядит для пользователя как поломка сети`() = runBlocking {
        // Кончился дневной бюджет владельца — 402. Пользователю про чужие деньги знать
        // незачем, но код отказа обязан остаться машинным.
        server.enqueue(registerResponse())
        server.enqueue(errorResponse(402, "daily_budget_exceeded"))

        val error = runCatching { client().sendMessage("привет").toList() }.exceptionOrNull()

        assertEquals("daily_budget_exceeded", (error as MeerBotError).code)
        assertEquals(R.string.meerbot_err_quota, error.messageRes)
    }

    @Test
    fun `обрыв посреди потока не теряет полученное`() = runBlocking {
        server.enqueue(registerResponse())
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
        server.enqueue(registerResponse())
        server.enqueue(sse("event: error\ndata: {\"code\":\"ai_unavailable\",\"message\":\"нет\"}\n\n"))

        val events = client().sendMessage("привет").toList()

        assertEquals(ChatStreamEvent.ServerError("ai_unavailable", "нет"), events.single())
    }

    // ─── История ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `история догоняется вместе с режимом диалога`() = runBlocking {
        server.enqueue(registerResponse())
        server.enqueue(
            MockResponse().setBody(
                """{"messages":[
                   {"id":1,"role":"user","content":"привет","createdAt":"2026-08-14T10:00:00.000Z"},
                   {"id":2,"role":"assistant","content":"на связи","authorName":"Марат","createdAt":"2026-08-14T10:00:01.000Z"}
                ],"hasMore":false,"mode":"human"}"""
            )
        )
        val api = client()

        val page = api.history()

        assertEquals(2, page.messages.size)
        assertEquals(ChatMode.Human, page.mode)
        // Имя автора обязано доехать: иначе ответ менеджера в ленте выглядит как ответ ИИ.
        assertEquals("Марат", page.messages[1].authorName)
        assertEquals(2L, api.lastMessageId)

        server.takeRequest()
        val request = server.takeRequest()
        assertTrue(request.path!!.startsWith("/api/v1/mobile/messages?"))
        // Диалог в запросе не указывается — сервер знает его по устройству.
        assertTrue(!request.path!!.contains("conversationId"))
        assertEquals("Bearer jwt-1", request.getHeader("Authorization"))
    }

    @Test
    fun `пустая история — не ошибка`() = runBlocking {
        server.enqueue(registerResponse())
        server.enqueue(MockResponse().setBody("""{"messages":[],"hasMore":false,"mode":"ai"}"""))

        val page = client().history()

        assertTrue(page.messages.isEmpty())
        assertEquals(ChatMode.Ai, page.mode)
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
    fun `рукопожатие без токена — некорректный ответ`() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"deviceId":"1"}"""))

        val error = runCatching { client().openSession() }.exceptionOrNull()

        assertTrue(error is MeerBotError.InvalidResponse)
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
        const val INSTALLATION = "and-22222222-2222-2222-2222-222222222222"
    }
}
