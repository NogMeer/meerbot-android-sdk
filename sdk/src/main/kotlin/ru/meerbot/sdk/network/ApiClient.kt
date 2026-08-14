package ru.meerbot.sdk.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONObject
import ru.meerbot.sdk.state.ChatMode
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Ответ handshake `/api/v1/widget/session`. */
data class WidgetSession(
    val jwt: String,
    val expiresIn: Int,
    val conversationId: Long?,
    val mode: ChatMode,
    val title: String?,
    val greeting: String?,
)

/** Сообщение из истории `/api/v1/widget/messages`. */
data class HistoryMessage(
    val id: Long,
    val role: String,
    val content: String,
    val createdAtMs: Long,
)

/** Ответ `/api/v1/mobile/register`. */
data class DeviceRegistration(
    val deviceId: String,
    val attestationRequired: Boolean,
)

/**
 * Клиент платформы: держит JWT, обновляет его по истечении и стримит ответы.
 *
 * Потокобезопасен: handshake сериализован мьютексом, поэтому параллельные отправки не
 * выписывают по своему JWT (сервер держит jti-allowlist, лишние токены — мусор).
 */
class ApiClient(
    private val config: MeerBotConfiguration,
    private val visitorUuid: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    private val tokenMutex = Mutex()

    @Volatile
    private var jwt: String? = null

    @Volatile
    private var jwtExpiresAtMs: Long = 0L

    /** Диалог текущей сессии. Проставляется из handshake/`meta`, уходит в тело следующего запроса. */
    @Volatile
    var conversationId: Long? = null
        private set

    /** id последнего известного сообщения — точка догона после обрыва. */
    @Volatile
    var lastMessageId: Long? = null
        private set

    fun setConversationId(id: Long?) {
        conversationId = id
    }

    // ─── Handshake ────────────────────────────────────────────────────────────────────────

    /** Открыть сессию виджета. Повторный вызов выдаёт новый JWT на тот же visitorUuid. */
    suspend fun openSession(): WidgetSession = tokenMutex.withLock { openSessionLocked() }

    private suspend fun openSessionLocked(): WidgetSession {
        val body = JSONObject()
            .put("key", config.apiKey)
            .put("visitorUuid", visitorUuid)
            .put("hostOrigin", config.origin)

        val request = newRequest("/api/v1/widget/session")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val json = executeJson(request)
        val token = json.optStringOrNull("jwt") ?: throw MeerBotError.InvalidResponse
        val expiresIn = json.optInt("expiresIn", 0)
        if (expiresIn <= 0) throw MeerBotError.InvalidResponse

        jwt = token
        jwtExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000L

        val widget = json.optJSONObject("widget")
        val restored = json.optLongOrNull("conversationId")
        if (restored != null) conversationId = restored

        return WidgetSession(
            jwt = token,
            expiresIn = expiresIn,
            conversationId = restored,
            mode = ChatMode.from(json.optString("mode")),
            title = widget?.optStringOrNull("title"),
            greeting = widget?.optStringOrNull("greeting"),
        )
    }

    /**
     * Действующий JWT: переиспользуем, пока до истечения больше минуты, иначе — новый handshake.
     * Параллельные вызовы ждут на мьютексе и получают уже обновлённый токен.
     */
    suspend fun validToken(): String = tokenMutex.withLock {
        val current = jwt
        if (current != null && jwtExpiresAtMs - System.currentTimeMillis() > TOKEN_MIN_LIFETIME_MS) {
            return@withLock current
        }
        openSessionLocked().jwt
    }

    /** Пометить текущий токен недействительным (сервер ответил 401 `jwt_*`). */
    fun invalidateToken() {
        jwt = null
        jwtExpiresAtMs = 0L
    }

    // ─── Регистрация устройства (FCM) ─────────────────────────────────────────────────────

    /** Зарегистрировать FCM-токен. Требует ключ мобильного приложения (`pushApiKey`). */
    suspend fun registerDevice(fcmToken: String): DeviceRegistration {
        val pushApiKey = config.pushApiKey
        if (pushApiKey.isNullOrEmpty()) throw MeerBotError.NotConfigured

        val body = JSONObject()
            .put("key", pushApiKey)
            .put("deviceToken", fcmToken)
            .put("platform", "android")
            .put("visitorUuid", visitorUuid)
            .put("sdkVersion", config.sdkVersion)

        val request = newRequest("/api/v1/mobile/register")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val json = executeJson(request)
        val deviceId = json.optStringOrNull("deviceId") ?: throw MeerBotError.InvalidResponse
        // JWT из этого ответа СОЗНАТЕЛЬНО не сохраняем: он подписан на пространство id мобильного
        // приложения и для /widget/chat/stream невалиден (см. MeerBotConfiguration).
        return DeviceRegistration(
            deviceId = deviceId,
            attestationRequired = json.optBoolean("attestationRequired", false),
        )
    }

    // ─── История (догон после обрыва) ─────────────────────────────────────────────────────

    /**
     * История диалога. Без `since` возвращает последние [limit] сообщений треда — именно это
     * нужно для замены ленты после обрыва; `since` — инкрементальный догон.
     */
    suspend fun history(since: Long? = null, limit: Int = 50): List<HistoryMessage> {
        val conversation = conversationId ?: return emptyList()

        val url = (config.baseUrl.trimEnd('/') + "/api/v1/widget/messages").toHttpUrl()
            .newBuilder()
            .addQueryParameter("conversationId", conversation.toString())
            .addQueryParameter("limit", limit.toString())
            .apply { if (since != null) addQueryParameter("since", since.toString()) }
            .build()

        val json = executeAuthorizedJson { token ->
            Request.Builder()
                .url(url)
                .get()
                .applyCommonHeaders()
                .header("Authorization", "Bearer $token")
                .build()
        }

        val raw = json.optJSONArray("messages") ?: throw MeerBotError.InvalidResponse
        val messages = ArrayList<HistoryMessage>(raw.length())
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val id = item.optLongOrNull("id") ?: continue
            val role = item.optStringOrNull("role") ?: continue
            val content = item.optString("content")
            messages += HistoryMessage(
                id = id,
                role = role,
                content = content,
                createdAtMs = parseTimestamp(item.optStringOrNull("createdAt")),
            )
        }
        messages.lastOrNull()?.let { lastMessageId = it.id }
        return messages
    }

    // ─── Стрим ответа ─────────────────────────────────────────────────────────────────────

    /**
     * Отправить сообщение и получить поток событий.
     *
     * События эмитятся по мере поступления. При обрыве поток выбрасывает
     * [MeerBotError.Network] — уже доставленные события остаются доставленными, вызывающая
     * сторона решает, догонять ли историю. Истёкший JWT (401 `jwt_*`) обновляется прозрачно,
     * запрос повторяется РОВНО один раз.
     */
    fun sendMessage(text: String): Flow<ChatStreamEvent> = flow {
        runStream(text, allowRetry = true, collector = this)
    }.flowOn(Dispatchers.IO)

    private suspend fun runStream(
        text: String,
        allowRetry: Boolean,
        collector: FlowCollector<ChatStreamEvent>,
    ) {
        val body = JSONObject().put("message", text)
        conversationId?.let { body.put("conversationId", it) }

        val request = newRequest("/api/v1/widget/chat/stream")
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${validToken()}")
            .build()

        // Читающий таймаут заметно больше 15-секундного heartbeat: молчание дольше этого —
        // мёртвое соединение, а не пауза в генерации.
        val call = httpClient.newBuilder()
            .readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
            .newCall(request)

        // Отмена корутины должна рвать сокет: readUtf8Line() блокирующий и сам её не заметит.
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw MeerBotError.Network(e.message ?: "io")
            }

            response.use {
                if (!it.isSuccessful) {
                    val error = decodeError(it.code, it.body?.string())
                    // Единственный автоматический повтор — на протухший токен.
                    if (allowRetry && error.isExpiredToken) {
                        invalidateToken()
                        runStream(text, allowRetry = false, collector = collector)
                        return
                    }
                    throw error
                }

                val source = it.body?.source() ?: throw MeerBotError.InvalidResponse
                try {
                    SseReader(source).read { raw ->
                        currentCoroutineContext().ensureActive()
                        emit(raw, collector)
                    }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw MeerBotError.Network(e.message ?: "stream_broken")
                }
            }
        } finally {
            cancelHandle?.dispose()
        }
    }

    private suspend fun emit(raw: SseEvent, collector: FlowCollector<ChatStreamEvent>) {
        val event = ChatStreamEvent.from(raw) ?: return
        if (event is ChatStreamEvent.Meta && event.conversationId > 0) {
            conversationId = event.conversationId
        }
        if (event is ChatStreamEvent.Manager && event.message.messageId > 0) {
            lastMessageId = event.message.messageId
        }
        collector.emit(event)
    }

    // ─── Транспорт ────────────────────────────────────────────────────────────────────────

    private fun newRequest(path: String): Request.Builder =
        Request.Builder()
            .url(config.baseUrl.trimEnd('/') + path)
            .applyCommonHeaders()

    private fun Request.Builder.applyCommonHeaders(): Request.Builder =
        // Origin здесь — обычный заголовок (в отличие от браузера): сервер пинует по нему
        // публичный ключ и сверяет с `oid` в JWT.
        header("Origin", config.origin)
            .header("X-SDK-Version", config.sdkVersion)

    /** Запрос без Authorization (handshake, регистрация устройства). */
    private suspend fun executeJson(request: Request): JSONObject = withContext(Dispatchers.IO) {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw MeerBotError.Network(e.message ?: "io")
        }
        response.use { parseJson(it) }
    }

    /** Запрос с Authorization: 401 по протухшему JWT обновляет сессию и повторяется один раз. */
    private suspend fun executeAuthorizedJson(build: (String) -> Request): JSONObject {
        return try {
            executeJson(build(validToken()))
        } catch (e: MeerBotError) {
            if (!e.isExpiredToken) throw e
            invalidateToken()
            executeJson(build(validToken()))
        }
    }

    private fun parseJson(response: Response): JSONObject {
        val text = response.body?.string()
        if (!response.isSuccessful) throw decodeError(response.code, text)
        if (text.isNullOrEmpty()) throw MeerBotError.InvalidResponse
        return runCatching { JSONObject(text) }.getOrElse { throw MeerBotError.InvalidResponse }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TOKEN_MIN_LIFETIME_MS = 60_000L
        private const val STREAM_READ_TIMEOUT_S = 60L

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /** Ошибки платформы приходят в форме Stripe/OpenAI: `{error:{type,code,message}}`. */
        fun decodeError(status: Int, body: String?): MeerBotError.Http {
            val error = body
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optJSONObject("error")
            return MeerBotError.Http(
                status = status,
                errorCode = error?.optStringOrNull("code") ?: "http_$status",
                serverMessage = error?.optStringOrNull("message") ?: "HTTP $status",
            )
        }

        private val ISO_FORMATS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )

        /** `createdAt` сервера — ISO-8601. Не разобрали — берём «сейчас», порядок ленты важнее. */
        fun parseTimestamp(raw: String?): Long {
            if (raw.isNullOrEmpty()) return System.currentTimeMillis()
            for (pattern in ISO_FORMATS) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(raw)
                }.getOrNull()
                if (parsed != null) return parsed.time
            }
            return System.currentTimeMillis()
        }

        internal fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        internal fun JSONObject.optLongOrNull(key: String): Long? =
            if (isNull(key)) null else optLong(key, -1L).takeIf { it >= 0L }
    }
}
