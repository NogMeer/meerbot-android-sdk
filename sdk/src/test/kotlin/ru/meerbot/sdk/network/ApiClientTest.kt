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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import ru.meerbot.sdk.R
import ru.meerbot.sdk.state.ChatMode
import ru.meerbot.sdk.testing.ScriptedDispatcher
import java.util.concurrent.TimeUnit

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

    private fun client(logoutFlag: LogoutFlagStore = InMemoryLogoutFlagStore()) = ApiClient(
        config = MeerBotConfiguration(
            apiKey = "pk_live_mobile",
            baseUrl = server.url("/").toString().trimEnd('/'),
            sdkVersion = "0.2.0-test",
        ),
        visitorUuid = VISITOR,
        installationId = INSTALLATION,
        httpClient = ApiClient.defaultHttpClient(),
        logoutFlag = logoutFlag,
    )

    /** Сервер с воротами: ответ рукопожатия уходит только по сигналу теста. */
    private fun gated(): ScriptedDispatcher = ScriptedDispatcher().also { server.dispatcher = it }

    /** `unlinked = null` — ответ старого сервера, который поля не знает. */
    private fun registerResponse(
        jwt: String = "jwt-1",
        expiresIn: Int = 900,
        identityStatus: String = "not_provided",
        attestationRequired: Boolean = false,
        unlinked: Boolean? = null,
    ) = MockResponse().setBody(
        JSONObject()
            .put("deviceId", "42")
            .put("jwt", jwt)
            .put("expiresIn", expiresIn)
            .put("attestationRequired", attestationRequired)
            .put(
                "identity",
                JSONObject().put("status", identityStatus)
                    .apply { if (unlinked != null) put("unlinked", unlinked) },
            )
            .toString()
    )

    private fun registerBody() = JSONObject(server.takeRequest().body.readUtf8())

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

    // ─── Выход ────────────────────────────────────────────────────────────────────────────

    @Test
    fun `без выхода поле logout в рукопожатие не уходит`() = runBlocking {
        // Сервер держит связь устройства, пока не придёт явный выход: лишний `logout` отвязал
        // бы вошедшего пользователя на каждом старте.
        server.enqueue(registerResponse())

        client().openSession()

        assertFalse(registerBody().has("logout"))
    }

    @Test
    fun `выход уходит в рукопожатие без токена идентичности`() = runBlocking {
        server.enqueue(registerResponse(identityStatus = "verified"))
        server.enqueue(registerResponse(unlinked = true))
        val api = client()
        api.setIdentityToken("signed")
        api.openSession()

        api.logout()
        api.openSession()

        assertEquals("signed", registerBody().getString("identityToken"))
        val body = registerBody()
        assertTrue(body.getBoolean("logout"))
        assertFalse(body.has("identityToken"))
    }

    @Test
    fun `подтверждённый сервером выход больше не повторяется`() = runBlocking {
        val flag = InMemoryLogoutFlagStore()
        server.enqueue(registerResponse(unlinked = true))
        server.enqueue(registerResponse(jwt = "jwt-2"))
        val api = client(flag)

        api.logout()
        api.openSession()
        assertFalse(flag.pending)
        api.invalidateToken()
        api.openSession()

        assertTrue(registerBody().getBoolean("logout"))
        assertFalse(registerBody().has("logout"))
    }

    @Test
    fun `unlinked false тоже подтверждает выход`() = runBlocking {
        // Устройство без связи: отвязывать нечего, но сервер выход понял — слать снова незачем.
        val flag = InMemoryLogoutFlagStore()
        server.enqueue(registerResponse(unlinked = false))
        val api = client(flag)

        api.logout()
        api.openSession()

        assertFalse(flag.pending)
    }

    @Test
    fun `старый сервер без unlinked получает выход снова`() = runBlocking {
        val flag = InMemoryLogoutFlagStore()
        server.enqueue(registerResponse())
        server.enqueue(registerResponse(jwt = "jwt-2"))
        val api = client(flag)

        api.logout()
        api.openSession()
        assertTrue(flag.pending)
        api.invalidateToken()
        api.openSession()

        assertTrue(registerBody().getBoolean("logout"))
        assertTrue(registerBody().getBoolean("logout"))
    }

    @Test
    fun `выход из прошлого запуска уходит в первое рукопожатие`() = runBlocking {
        // Флаг лежит в хранилище, а клиент создан заново — как после перезапуска процесса.
        server.enqueue(registerResponse(unlinked = true))
        val flag = InMemoryLogoutFlagStore(pending = true)

        client(flag).openSession()

        assertTrue(registerBody().getBoolean("logout"))
        assertFalse(flag.pending)
    }

    @Test
    fun `выход сбрасывает сессию сразу`() = runBlocking {
        server.enqueue(registerResponse(jwt = "jwt-linked", identityStatus = "verified"))
        server.enqueue(registerResponse(jwt = "jwt-anon", unlinked = true))
        val api = client()
        api.setIdentityToken("signed")
        assertEquals("jwt-linked", api.validToken())
        api.rememberConversationId(77)

        api.logout()

        assertNull(api.conversationId)
        assertEquals(IdentityStatus.NotProvided, api.identityStatus)
        assertEquals("jwt-anon", api.validToken())
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `выход и новый токен уходят одним рукопожатием`() = runBlocking {
        // Сервер решает сам: другой пользователь — новая строка, тот же — связь остаётся.
        server.enqueue(registerResponse(identityStatus = "verified", unlinked = true))
        val api = client()

        api.logout()
        api.setIdentityToken("B")
        api.openSession()

        val body = registerBody()
        assertTrue(body.getBoolean("logout"))
        assertEquals("B", body.getString("identityToken"))
    }

    /**
     * Рукопожатие ушло до выхода, ответ пришёл после. Его JWT выписан на устройство прежнего
     * пользователя: сохранить его — значит открыть следующему человеку чужую ленту.
     */
    @Test
    fun `рукопожатие, начатое до выхода, своей сессии не сохраняет`() = runBlocking {
        val flag = InMemoryLogoutFlagStore()
        val dispatcher = gated()
        val gate = dispatcher.gateNextRegister(registerResponse(jwt = "jwt-linked", identityStatus = "verified"))
        dispatcher.registerFallback = { registerResponse(jwt = "jwt-anon", unlinked = true) }
        val api = client(flag)
        api.setIdentityToken("signed")

        val token = withContext(Dispatchers.Default) {
            val pending = async { api.validToken() }
            assertEquals("signed", JSONObject(dispatcher.awaitRegister().body.readUtf8()).getString("identityToken"))
            api.logout()
            gate.countDown()
            pending.await()
        }

        assertEquals("jwt-anon", token)
        val retry = JSONObject(dispatcher.awaitRegister().body.readUtf8())
        assertTrue(retry.getBoolean("logout"))
        assertFalse(retry.has("identityToken"))
        assertFalse(flag.pending)
        assertEquals("jwt-anon", api.validToken())
        assertEquals(2, server.requestCount)
    }

    /**
     * Ответ прежнему пользователю чужой, даже битый: его ошибка показала бы новому человеку
     * провал запроса, которого тот не делал. Поколение сверяется до разбора тела.
     */
    @Test
    fun `битый ответ прежнему пользователю уходит в повтор, а не в ошибку`() = runBlocking {
        val dispatcher = gated()
        val gate = dispatcher.gateNextRegister(MockResponse().setBody("<html>gateway</html>"))
        dispatcher.registerFallback = { registerResponse(jwt = "jwt-B") }
        val api = client()

        val token = withContext(Dispatchers.Default) {
            val pending = async { api.validToken() }
            dispatcher.awaitRegister()
            api.setIdentityToken("B")
            gate.countDown()
            pending.await()
        }

        assertEquals("jwt-B", token)
        assertEquals("B", JSONObject(dispatcher.awaitRegister().body.readUtf8()).getString("identityToken"))
    }

    @Test
    fun `две смены пользователя в полёте исчерпывают обе попытки`() = runBlocking {
        val dispatcher = gated()
        val first = dispatcher.gateNextRegister()
        val second = dispatcher.gateNextRegister()
        val api = client()

        val error = withContext(Dispatchers.Default) {
            val pending = async { runCatching { api.validToken() }.exceptionOrNull() }
            dispatcher.awaitRegister()
            api.setIdentityToken("B")
            first.countDown()
            dispatcher.awaitRegister()
            api.setIdentityToken("C")
            second.countDown()
            pending.await()
        }

        assertEquals(MeerBotError.Cancelled, error)
        assertEquals(2, server.requestCount)
    }

    /**
     * Хост выпускает токен на каждый вход в чат. Ответ со старым токеном того же человека не
     * чужой: его JWT отдаётся ждущему запросу, но не кэшируется. Повтора нет — он съедал бы
     * попытку и делал лишний `/register`; свежий токен уходит со следующим рукопожатием.
     */
    @Test
    fun `свежий токен того же пользователя в полёте не повторяет рукопожатие и не кэширует ответ`() = runBlocking {
        val dispatcher = gated()
        val first = dispatcher.gateNextRegister(registerResponse(jwt = "jwt-1", identityStatus = "rejected"))
        dispatcher.registerFallback = { registerResponse(jwt = "jwt-2", identityStatus = "verified") }
        val api = client()
        api.setIdentityToken("A1")

        val token = withContext(Dispatchers.Default) {
            val pending = async { api.validToken() }
            dispatcher.awaitRegister()
            api.refreshIdentityToken("A2")
            first.countDown()
            pending.await()
        }

        assertEquals("jwt-1", token)
        assertEquals(1, server.requestCount)
        // Статус прежнего токена не публикуется: хост уже передал свежий.
        assertEquals(IdentityStatus.NotProvided, api.identityStatus)

        assertEquals("jwt-2", api.validToken())
        assertEquals(IdentityStatus.from("verified"), api.identityStatus)
        assertEquals("A2", JSONObject(dispatcher.awaitRegister().body.readUtf8()).getString("identityToken"))
        assertEquals(2, server.requestCount)
    }

    /**
     * `identify(null)` записал выход на диск, пока летело рукопожатие с прежним выходом. Ответ
     * на прежний не имеет права снять новый: главный поток его ещё не применил, и убитый в этом
     * окне процесс забыл бы выход.
     */
    @Test
    fun `выход, записанный в полёте, не снимается ответом на прежний`() = runBlocking {
        val flag = InMemoryLogoutFlagStore()
        val dispatcher = gated()
        val gate = dispatcher.gateNextRegister(registerResponse(unlinked = true))
        val api = client(flag)
        api.logout()

        withContext(Dispatchers.Default) {
            val pending = async { api.openSession() }
            assertTrue(JSONObject(dispatcher.awaitRegister().body.readUtf8()).getBoolean("logout"))
            api.persistLogoutIntent()
            gate.countDown()
            pending.await()
        }

        assertTrue(flag.pending)
    }

    @Test
    fun `новый токен сбрасывает диалог, курсор и статус прежней identity`() = runBlocking {
        server.enqueue(registerResponse(identityStatus = "verified"))
        val api = client()
        api.setIdentityToken("A")
        api.openSession()
        api.rememberConversationId(77)
        assertTrue(api.identityStatus != IdentityStatus.NotProvided)

        api.setIdentityToken("B")

        assertNull(api.conversationId)
        assertNull(api.lastMessageId)
        assertEquals(IdentityStatus.NotProvided, api.identityStatus)
    }

    /** Поток прежнего человека дописал `meta` уже после смены: диалог новому не достаётся. */
    @Test
    fun `поздний meta потока прежней identity не пишет диалог`() = runBlocking {
        val dispatcher = gated()
        val streamGate = dispatcher.gateNextStream(
            sse("event: meta\ndata: {\"conversationId\":3,\"mode\":\"ai\"}\n\ndata: [DONE]\n\n"),
        )
        val api = client()

        val events = withContext(Dispatchers.Default) {
            val pending = async { api.sendMessage("привет").toList() }
            dispatcher.awaitStream()
            api.setIdentityToken("B")
            streamGate.countDown()
            pending.await()
        }

        assertTrue(events.any { it is ChatStreamEvent.Meta })
        assertNull(api.conversationId)
    }

    @Test
    fun `страница истории прежней identity не двигает курсор`() = runBlocking {
        val dispatcher = gated()
        val gate = dispatcher.gateNextHistory(
            ScriptedDispatcher.history(
                messages = """{"id":9,"role":"assistant","content":"x","createdAt":"2026-08-14T10:00:00.000Z"}""",
            ),
        )
        val api = client()

        withContext(Dispatchers.Default) {
            val pending = async { api.history() }
            dispatcher.awaitHistory()
            api.setIdentityToken("B")
            gate.countDown()
            pending.await()
        }

        assertNull(api.lastMessageId)
    }

    @Test
    fun `смена пользователя без выхода уходит выходом и новым токеном`() = runBlocking {
        server.enqueue(registerResponse(identityStatus = "verified"))
        server.enqueue(registerResponse(identityStatus = "rejected", unlinked = true))
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        api.setIdentityToken("A")
        api.openSession()
        api.rememberConversationId(77)

        api.switchIdentity("B")

        assertTrue(flag.pending)
        assertNull(api.conversationId)
        assertNull(api.lastMessageId)
        assertEquals(IdentityStatus.NotProvided, api.identityStatus)
        api.openSession()
        assertEquals("A", registerBody().getString("identityToken"))
        val body = registerBody()
        assertTrue(body.getBoolean("logout"))
        assertEquals("B", body.getString("identityToken"))
        assertFalse(flag.pending)
    }

    // ─── Публичный setIdentityToken: флаг выхода, как у identify (паритет с iOS) ─────────────

    @Test
    fun `setIdentityToken с другим sub ставит флаг выхода`() = runBlocking {
        server.enqueue(registerResponse(identityStatus = "verified", unlinked = true))
        server.enqueue(registerResponse(jwt = "jwt-2", identityStatus = "rejected", unlinked = true))
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        val tokenB = Jwt.withSub("user-B")

        api.setIdentityToken(Jwt.withSub("user-A"))
        // Первый токен экземпляра: кто был связан с устройством до него, неизвестно.
        assertTrue(flag.pending)
        api.openSession()
        assertFalse(flag.pending)
        api.rememberConversationId(77)

        api.setIdentityToken(tokenB)

        assertTrue(flag.pending)
        assertNull(api.conversationId)
        assertEquals(IdentityStatus.NotProvided, api.identityStatus)
        api.openSession()
        assertTrue(registerBody().getBoolean("logout"))
        val body = registerBody()
        assertTrue(body.getBoolean("logout"))
        assertEquals(tokenB, body.getString("identityToken"))
        assertFalse(flag.pending)
    }

    @Test
    fun `setIdentityToken со свежим токеном того же sub выход не ставит и диалог не сбрасывает`() = runBlocking {
        server.enqueue(registerResponse(identityStatus = "verified", unlinked = true))
        server.enqueue(registerResponse(jwt = "jwt-2", identityStatus = "verified"))
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        api.setIdentityToken(Jwt.withSub("user-A", iat = 1))
        api.validToken()
        assertFalse(flag.pending)
        api.rememberConversationId(77)
        val fresh = Jwt.withSub("user-A", iat = 2)

        api.setIdentityToken(fresh)

        assertFalse(flag.pending)
        assertEquals(77L, api.conversationId)
        assertEquals("jwt-2", api.validToken())
        registerBody()
        val body = registerBody()
        assertFalse(body.has("logout"))
        assertEquals(fresh, body.getString("identityToken"))
    }

    /** Нечитаемый `sub` — не «тот же человек», даже если строка токена та же (как identify). */
    @Test
    fun `setIdentityToken с нечитаемым sub ставит флаг выхода`() = runBlocking {
        server.enqueue(registerResponse(unlinked = true))
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        api.setIdentityToken("opaque")
        api.openSession()
        assertFalse(flag.pending)

        api.setIdentityToken("opaque")

        assertTrue(flag.pending)
    }

    @Test
    fun `setIdentityToken null сбрасывает диалог, но выход не ставит`() = runBlocking {
        server.enqueue(registerResponse(identityStatus = "verified", unlinked = true))
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        api.setIdentityToken(Jwt.withSub("user-A"))
        api.openSession()
        api.rememberConversationId(77)

        api.setIdentityToken(null)

        assertFalse(flag.pending)
        assertNull(api.conversationId)
        assertEquals(IdentityStatus.NotProvided, api.identityStatus)
    }

    /** Клиент хоста из публичного конструктора: флаг в памяти, но в рукопожатие он уходит. */
    @Test
    fun `клиент из публичного конструктора шлёт выход после смены токена`() = runBlocking {
        server.enqueue(registerResponse(unlinked = true))
        val api = ApiClient(
            MeerBotConfiguration(
                apiKey = "pk_live_mobile",
                baseUrl = server.url("/").toString().trimEnd('/'),
                sdkVersion = "0.2.0-test",
            ),
            VISITOR,
            INSTALLATION,
        )

        api.setIdentityToken(Jwt.withSub("user-A"))
        api.openSession()

        assertTrue(registerBody().getBoolean("logout"))
    }

    @Test
    fun `свежий токен того же пользователя выход не шлёт и диалог не сбрасывает`() = runBlocking {
        // `unlinked` снимает флаг, поставленный первым токеном экземпляра.
        server.enqueue(registerResponse(identityStatus = "verified", unlinked = true))
        server.enqueue(registerResponse(jwt = "jwt-2", identityStatus = "verified"))
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        api.setIdentityToken("A1")
        api.validToken()
        api.rememberConversationId(77)

        api.refreshIdentityToken("A2")

        assertEquals("jwt-2", api.validToken())
        registerBody()
        val body = registerBody()
        assertFalse(body.has("logout"))
        assertEquals("A2", body.getString("identityToken"))
        assertFalse(flag.pending)
        assertEquals(77L, api.conversationId)
    }

    @Test
    fun `unlinked не булевым значением выход не подтверждает`() = runBlocking {
        val flag = InMemoryLogoutFlagStore()
        server.enqueue(
            MockResponse().setBody("""{"deviceId":"42","jwt":"j1","expiresIn":900,"identity":{"status":"not_provided","unlinked":"true"}}"""),
        )
        server.enqueue(
            MockResponse().setBody("""{"deviceId":"42","jwt":"j2","expiresIn":900,"identity":{"status":"not_provided","unlinked":null}}"""),
        )
        val api = client(flag)
        api.logout()

        api.openSession()
        assertTrue(flag.pending)
        api.invalidateToken()
        api.openSession()

        assertTrue(flag.pending)
    }

    @Test
    fun `снятое с регистрации устройство переподключается и запрос повторяется один раз`() = runBlocking {
        // После выхода токен прежней сессии указывает на устройство в отставке.
        server.enqueue(registerResponse(jwt = "jwt-old"))
        server.enqueue(errorResponse(401, "device_not_found"))
        server.enqueue(registerResponse(jwt = "jwt-new"))
        server.enqueue(MockResponse().setBody("""{"messages":[],"hasMore":false,"mode":"ai"}"""))

        client().history()

        assertEquals(4, server.requestCount)
        server.takeRequest()
        assertEquals("Bearer jwt-old", server.takeRequest().getHeader("Authorization"))
        server.takeRequest()
        assertEquals("Bearer jwt-new", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `токен без устройства тоже лечится новым рукопожатием`() {
        assertTrue(ApiClient.decodeError(401, """{"error":{"code":"device_claim_missing"}}""").isExpiredToken)
        assertTrue(ApiClient.decodeError(401, """{"error":{"code":"device_not_found"}}""").isExpiredToken)
        assertFalse(ApiClient.decodeError(403, """{"error":{"code":"channel_mismatch"}}""").isExpiredToken)
        // До экрана она доходит, только когда переподключение уже не помогло.
        assertEquals(
            R.string.meerbot_err_session_lost,
            ApiClient.decodeError(401, """{"error":{"code":"device_not_found"}}""").messageRes,
        )
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
