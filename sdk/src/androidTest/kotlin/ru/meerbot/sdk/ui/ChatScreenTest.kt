package ru.meerbot.sdk.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import ru.meerbot.sdk.R
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.MeerBotConfiguration
import ru.meerbot.sdk.state.ChatController

/**
 * Экран против настоящего состояния и настоящего (замоканного) сервера.
 *
 * Юнит-тесты проверяют поведение контроллера, но не то, что экран к нему подключён — а
 * ровно эта связка и была разорвана в каркасе (кнопка отправки звала демо-заглушку).
 * Гоняется на устройстве: `./gradlew :sdk:connectedDebugAndroidTest`.
 *
 * Тексты берутся ресурсами, а не литералами: тесты не должны зависеть от локали устройства.
 */
@RunWith(AndroidJUnit4::class)
class ChatScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private lateinit var server: MockWebServer
    private lateinit var scope: CoroutineScope

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    private fun str(resId: Int): String = context.getString(resId)

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() {
        scope.cancel()
        server.shutdown()
    }

    private fun session(greeting: String? = null, mode: String = "ai") =
        MockResponse().setResponseCode(201).setBody(
            buildString {
                append("""{"jwt":"jwt-1","expiresIn":900,"mode":"$mode"""")
                append(""","widget":{"title":"Поддержка"""")
                if (greeting != null) append(""","greeting":"$greeting"""")
                append("}}")
            }
        )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun showScreen() {
        val controller = ChatController(
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
        compose.setContent {
            MaterialTheme {
                ChatScreen(controller = controller)
            }
        }
    }

    @Test
    fun приветствие_канала_показывается_в_пустой_ленте() {
        server.enqueue(session(greeting = "Чем можем помочь?"))

        showScreen()

        compose.awaitText("Чем можем помочь?")
        compose.onNodeWithText("Чем можем помочь?").assertIsDisplayed()
    }

    @Test
    fun отправка_добавляет_сообщение_и_стримит_ответ() {
        server.enqueue(session())
        server.enqueue(
            sse(
                "event: meta\ndata: {\"conversationId\":3,\"mode\":\"ai\"}\n\n" +
                    "data: {\"choices\":[{\"delta\":{\"content\":\"Готово\"}}]}\n\n" +
                    "data: [DONE]\n\n"
            )
        )

        showScreen()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.meerbot_input_hint)).performTextInput("привет")
        compose.onNodeWithContentDescription(str(R.string.meerbot_send)).performClick()

        compose.awaitText("Готово")
        compose.onNodeWithText("привет").assertIsDisplayed()
    }

    @Test
    fun обрыв_показывает_повтор_и_метку_недоставленного() {
        server.enqueue(session())
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )

        showScreen()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.meerbot_input_hint)).performTextInput("привет")
        compose.onNodeWithContentDescription(str(R.string.meerbot_send)).performClick()

        compose.awaitText(str(R.string.meerbot_retry))
        compose.onNodeWithText(str(R.string.meerbot_not_delivered)).assertIsDisplayed()
    }

    @Test
    fun закрытый_диалог_блокирует_ввод() {
        server.enqueue(session(mode = "closed"))

        showScreen()

        compose.awaitText(str(R.string.meerbot_input_hint_closed))
        compose.onNodeWithContentDescription(str(R.string.meerbot_send)).assertIsNotEnabled()
    }
}

/** Дождаться появления текста: сеть здесь настоящая, ожидание по семантике надёжнее пауз. */
private fun ComposeContentTestRule.awaitText(text: String, timeoutMs: Long = 10_000) {
    waitUntil(timeoutMs) {
        onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty()
    }
}
