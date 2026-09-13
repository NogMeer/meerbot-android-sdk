package ru.meerbot.sdk.testing

import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.fail
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * MockWebServer с ответами по пути и «воротами»: запрос долетает до сервера, а ответ уходит
 * только по сигналу теста. Так гонка «запрос в полёте ↔ смена пользователя» воспроизводится
 * порядком действий, а не паузами `Thread.sleep`, которые мигают на CI и не ловят откат фикса.
 */
class ScriptedDispatcher : Dispatcher() {

    private class Step(val response: MockResponse, val gate: CountDownLatch?)

    private val registerSteps = ConcurrentLinkedQueue<Step>()
    private val historySteps = ConcurrentLinkedQueue<Step>()
    private val streamSteps = ConcurrentLinkedQueue<Step>()
    private val registerArrivals = LinkedBlockingQueue<RecordedRequest>()
    private val historyArrivals = LinkedBlockingQueue<RecordedRequest>()
    private val streamArrivals = LinkedBlockingQueue<RecordedRequest>()

    /** Ответ рукопожатия, когда сценарий исчерпан. */
    @Volatile
    var registerFallback: () -> MockResponse = { register() }

    /** Ответ истории, когда сценарий исчерпан. */
    @Volatile
    var historyFallback: () -> MockResponse = { history() }

    /** Следующее рукопожатие ответит [response], но только после `countDown()` на воротах. */
    fun gateNextRegister(response: MockResponse = register()): CountDownLatch =
        CountDownLatch(1).also { registerSteps.add(Step(response, it)) }

    /** Ответ потока `/mobile/chat/stream`, когда сценарий исчерпан. */
    @Volatile
    var streamFallback: () -> MockResponse = { MockResponse().setResponseCode(404) }

    fun gateNextHistory(response: MockResponse): CountDownLatch =
        CountDownLatch(1).also { historySteps.add(Step(response, it)) }

    fun gateNextStream(response: MockResponse): CountDownLatch =
        CountDownLatch(1).also { streamSteps.add(Step(response, it)) }

    fun awaitStream(): RecordedRequest =
        streamArrivals.poll(5, TimeUnit.SECONDS) ?: failWith("запрос потока не пришёл")

    fun awaitRegister(): RecordedRequest =
        registerArrivals.poll(5, TimeUnit.SECONDS) ?: failWith("рукопожатие не пришло")

    fun awaitHistory(): RecordedRequest =
        historyArrivals.poll(5, TimeUnit.SECONDS) ?: failWith("запрос истории не пришёл")

    /** Сколько запросов истории пришло и ещё не забрано `awaitHistory`. */
    fun historyArrivalCount(): Int = historyArrivals.size

    fun clearArrivals() {
        registerArrivals.clear()
        historyArrivals.clear()
        streamArrivals.clear()
    }

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty()
        return when {
            path.startsWith("/api/v1/mobile/register") ->
                respond(request, registerArrivals, registerSteps, registerFallback)
            path.startsWith("/api/v1/mobile/messages") ->
                respond(request, historyArrivals, historySteps, historyFallback)
            path.startsWith("/api/v1/mobile/chat/stream") ->
                respond(request, streamArrivals, streamSteps, streamFallback)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun respond(
        request: RecordedRequest,
        arrivals: LinkedBlockingQueue<RecordedRequest>,
        steps: ConcurrentLinkedQueue<Step>,
        fallback: () -> MockResponse,
    ): MockResponse {
        arrivals.put(request)
        val step = steps.poll() ?: return fallback()
        step.gate?.await(10, TimeUnit.SECONDS)
        return step.response
    }

    private fun failWith(message: String): Nothing {
        fail(message)
        throw AssertionError(message)
    }

    companion object {
        fun register(jwt: String = "jwt-1") = MockResponse().setBody(
            """{"deviceId":"42","jwt":"$jwt","expiresIn":900,"attestationRequired":false,"identity":{"status":"not_provided"}}""",
        )

        fun history(mode: String = "ai", messages: String = "") = MockResponse().setBody(
            """{"messages":[$messages],"hasMore":false,"mode":"$mode"}""",
        )

        fun sse(body: String) = MockResponse()
            .setHeader("Content-Type", "text/event-stream")
            .setBody(body)

        fun error(status: Int, code: String) = MockResponse()
            .setResponseCode(status)
            .setBody("""{"error":{"code":"$code","message":"nope"}}""")
    }
}
