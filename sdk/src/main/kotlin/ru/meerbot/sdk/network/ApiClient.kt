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

/** Ответ рукопожатия `/api/v1/mobile/register`. */
data class MobileSession(
    val deviceId: String,
    val jwt: String,
    val expiresIn: Int,
    val attestationRequired: Boolean,
    val identityStatus: IdentityStatus,
)

/** Страница истории: сервер отдаёт вместе с сообщениями и текущий режим диалога. */
data class HistoryPage(
    val messages: List<HistoryMessage>,
    val hasMore: Boolean,
    val mode: ChatMode,
)

/** Сообщение из истории `/api/v1/mobile/messages`. */
data class HistoryMessage(
    val id: Long,
    val role: String,
    val content: String,
    /** `ai` | `manager` у ответов ассистентской роли, иначе `null`. Машинный дискриминатор:
     *  подпись (`authorName`) у менеджера может отсутствовать, а автор — нет. Сервер отдаёт
     *  поле с 2026-08-23; у старых сборок платформы его нет. */
    val authorKind: String?,
    val authorName: String?,
    val createdAtMs: Long,
)

/**
 * Клиент канала `mobile_app`: держит JWT, обновляет его по истечении и стримит ответы.
 *
 * Потокобезопасен: рукопожатие сериализовано мьютексом, поэтому параллельные отправки не
 * выписывают по своему JWT (сервер держит jti-allowlist, лишние токены — мусор).
 */
class ApiClient(
    private val config: MeerBotConfiguration,
    private val visitorUuid: String,
    /**
     * Стабильный идентификатор установки. Уходит в поле `deviceToken` рукопожатия: сервер
     * ключует по нему `MobileDevice`, а тред диалога — по устройству. Поэтому значение
     * обязано пережить и перезапуск, и получение пуш-токена: подменить его — значит начать
     * пользователю новую переписку с чистого листа.
     *
     * Пуш-токен сюда НЕ кладётся: своей отправки пушей у платформы нет, ответ менеджера
     * уходит вебхуком на бэкенд интегратора (план поставки 1, этап E).
     */
    private val installationId: String,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {

    private val tokenMutex = Mutex()

    @Volatile
    private var jwt: String? = null

    @Volatile
    private var jwtExpiresAtMs: Long = 0L

    @Volatile
    private var identityToken: String? = null

    /** Диалог текущего устройства. Приходит из `meta`; в запросы НЕ уходит. */
    @Volatile
    var conversationId: Long? = null
        private set

    /**
     * Запомнить id диалога из пуша (`MeerBot.handlePush`). В запросы он по-прежнему не уходит
     * — тред резолвится по устройству из токена; значение нужно ХОСТУ, чтобы не показывать
     * баннер о сообщении, открытом сейчас на экране. Паритет с iOS `setConversationId`.
     */
    internal fun rememberConversationId(id: Long) {
        conversationId = id
    }

    /** id последнего известного сообщения — точка догона после обрыва. */
    @Volatile
    var lastMessageId: Long? = null
        private set

    /** Статус identity с последнего рукопожатия. */
    @Volatile
    var identityStatus: IdentityStatus = IdentityStatus.NotProvided
        private set

    /**
     * Подписанный бэкендом интегратора токен идентичности. Следующее рукопожатие уйдёт с ним;
     * текущая сессия сбрасывается, иначе identity подхватилась бы только через 15 минут.
     */
    fun setIdentityToken(token: String?) {
        identityToken = token
        invalidateToken()
    }

    // ─── Рукопожатие ──────────────────────────────────────────────────────────────────────

    suspend fun openSession(): MobileSession = tokenMutex.withLock { openSessionLocked() }

    private suspend fun openSessionLocked(): MobileSession {
        val body = JSONObject()
            .put("key", config.apiKey)
            .put("deviceToken", installationId)
            .put("platform", "android")
            .put("visitorUuid", visitorUuid)
            .put("sdkVersion", config.sdkVersion)
        identityToken?.let { body.put("identityToken", it) }

        val request = newRequest("/api/v1/mobile/register")
            .post(body.toString().toRequestBody(JSON))
            .build()

        val json = executeJson(request)
        val token = json.optStringOrNull("jwt") ?: throw MeerBotError.InvalidResponse
        val deviceId = json.optStringOrNull("deviceId") ?: throw MeerBotError.InvalidResponse
        val expiresIn = json.optInt("expiresIn", 0)
        if (expiresIn <= 0) throw MeerBotError.InvalidResponse

        jwt = token
        jwtExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000L

        val status = IdentityStatus.from(json.optJSONObject("identity")?.optStringOrNull("status"))
        identityStatus = status

        return MobileSession(
            deviceId = deviceId,
            jwt = token,
            expiresIn = expiresIn,
            attestationRequired = json.optBoolean("attestationRequired", false),
            identityStatus = status,
        )
    }

    /**
     * Действующий JWT: переиспользуем, пока до истечения больше минуты, иначе — новое
     * рукопожатие. Параллельные вызовы ждут на мьютексе и получают уже обновлённый токен.
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

    // ─── История (догон после обрыва) ─────────────────────────────────────────────────────

    /**
     * История диалога. Диалог сервер резолвит по устройству из токена — передавать его id
     * клиенту нечем и незачем.
     */
    suspend fun history(since: Long? = null, limit: Int = 50): HistoryPage {
        val url = (config.baseUrl.trimEnd('/') + "/api/v1/mobile/messages").toHttpUrl()
            .newBuilder()
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
            messages += HistoryMessage(
                id = id,
                role = role,
                content = item.optString("content"),
                authorKind = item.optStringOrNull("authorKind"),
                authorName = item.optStringOrNull("authorName"),
                createdAtMs = parseTimestamp(item.optStringOrNull("createdAt")),
            )
        }
        messages.lastOrNull()?.let { lastMessageId = it.id }
        // Режим приходит той же страницей: только так клиент узнаёт, что диалог закрыт или
        // уже ведёт менеджер, — рукопожатие канала режима не отдаёт.
        return HistoryPage(
            messages = messages,
            hasMore = json.optBoolean("hasMore", false),
            mode = ChatMode.from(json.optStringOrNull("mode")),
        )
    }

    // ─── Стрим ответа ─────────────────────────────────────────────────────────────────────

    /**
     * Отправить сообщение и получить поток событий.
     *
     * События эмитятся по мере поступления. При обрыве поток выбрасывает
     * [MeerBotError.Network] — уже доставленные события остаются доставленными, вызывающая
     * сторона решает, догонять ли историю. Истёкший JWT (401 `jwt_*`) обновляется прозрачно,
     * запрос повторяется РОВНО один раз; 403 `channel_mismatch` не повторяется никогда —
     * перепутан ключ, и новый токен будет ровно таким же.
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

        val request = newRequest("/api/v1/mobile/chat/stream")
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

    // `Origin` не отправляется сознательно: у нативного приложения его нет, а сервер
    // мобильного канала по нему ничего не проверяет (тира лимитов по origin у канала тоже нет).
    private fun Request.Builder.applyCommonHeaders(): Request.Builder =
        header("X-SDK-Version", config.sdkVersion)

    /** Запрос без Authorization (рукопожатие). */
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

        /**
         * Отказы канала приходят в форме `{error:{code,message}}` — без поля `type`,
         * в отличие от веб-виджета. Читаем только `code`: он единственный машинный.
         */
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
