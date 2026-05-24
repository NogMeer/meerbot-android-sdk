package ru.meerbot.sdk.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.sse.EventSource
import okhttp3.sse.EventSourceListener
import okhttp3.sse.EventSources
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * MeerBot Android SDK — Phase 5.c: HTTP-клиент + SSE.
 * OkHttp + okhttp-sse extension. Cert pinning подключается из MeerBot.configure().
 */
class ApiClient(
    private val baseUrl: String,
    private val pkLive: String,
    private val origin: String,
    okHttpBuilder: OkHttpClient.Builder = OkHttpClient.Builder(),
) {
    private val httpClient: OkHttpClient = okHttpBuilder
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var jwt: String? = null
    private val jsonMedia = "application/json".toMediaTypeOrNull()

    fun setJWT(token: String) {
        jwt = token
    }

    data class SessionResponse(val jwt: String, val expiresIn: Int, val conversationId: Int?)

    suspend fun createSession(visitorUuid: String, externalUserId: String?): SessionResponse = withContext(Dispatchers.IO) {
        val payload = JSONObject().apply {
            put("key", pkLive)
            put("visitorUuid", visitorUuid)
            externalUserId?.let { put("externalUserId", it) }
        }
        val req = Request.Builder()
            .url("$baseUrl/api/v1/widget/session")
            .post(payload.toString().toRequestBody(jsonMedia))
            .header("Origin", origin)
            .build()
        val resp = httpClient.newCall(req).execute()
        resp.use {
            val body = resp.body?.string() ?: throw IllegalStateException("Empty response")
            if (!resp.isSuccessful) {
                throw IllegalStateException("HTTP ${resp.code}: $body")
            }
            val json = JSONObject(body)
            SessionResponse(
                jwt = json.getString("jwt"),
                expiresIn = json.optInt("expiresIn", 900),
                conversationId = if (json.has("conversationId")) json.optInt("conversationId") else null,
            )
        }
    }

    data class StreamChunk(val event: String, val data: Map<String, Any?>)

    fun openChatStream(conversationId: Int?, content: String): Flow<StreamChunk> = flow {
        val token = jwt ?: throw IllegalStateException("JWT not set")
        val payload = JSONObject().apply {
            put("content", content)
            conversationId?.let { put("conversationId", it) }
        }
        val req = Request.Builder()
            .url("$baseUrl/api/v1/widget/chat/stream")
            .post(payload.toString().toRequestBody(jsonMedia))
            .header("Authorization", "Bearer $token")
            .header("Origin", origin)
            .header("Accept", "text/event-stream")
            .build()

        val chunks = mutableListOf<StreamChunk>()
        suspendCancellableCoroutine<Unit> { cont ->
            val source = EventSources.createFactory(httpClient).newEventSource(
                req,
                object : EventSourceListener() {
                    override fun onEvent(eventSource: EventSource, id: String?, type: String?, data: String) {
                        val parsed = parseJson(data)
                        chunks.add(StreamChunk(event = type ?: "message", data = parsed))
                        if (type == "done" || type == "close") {
                            cont.resume(Unit)
                        }
                    }
                    override fun onClosed(eventSource: EventSource) {
                        if (cont.isActive) cont.resume(Unit)
                    }
                    override fun onFailure(eventSource: EventSource, t: Throwable?, response: Response?) {
                        if (cont.isActive) cont.resumeWithException(t ?: IllegalStateException("SSE failed"))
                    }
                },
            )
            cont.invokeOnCancellation { source.cancel() }
        }
        for (c in chunks) emit(c)
    }.flowOn(Dispatchers.IO)

    private fun parseJson(raw: String): Map<String, Any?> {
        return try {
            val obj = JSONObject(raw)
            val map = mutableMapOf<String, Any?>()
            for (key in obj.keys()) {
                map[key] = obj.get(key)
            }
            map
        } catch (e: Exception) {
            mapOf("raw" to raw)
        }
    }
}
