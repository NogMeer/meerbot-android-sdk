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

    private fun register() = MockResponse().setBody(
        """{"deviceId":"42","jwt":"jwt-1","expiresIn":900,"identity":{"status":"not_provided"}}"""
    )

    private fun history(mode: String = "ai", messages: String = "") = MockResponse().setBody(
        """{"messages":[$messages],"hasMore":false,"mode":"$mode"}"""
    )

    private fun sse(body: String) = MockResponse()
        .setHeader("Content-Type", "text/event-stream")
        .setBody(body)

    private fun showScreen() {
        val controller = ChatController(
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
        compose.setContent {
            MaterialTheme {
                ChatScreen(controller = controller)
            }
        }
    }

    @Test
    fun прошлая_переписка_подтягивается_при_открытии() {
        server.enqueue(register())
        server.enqueue(
            history(messages = """{"id":7,"role":"assistant","content":"мы на связи","createdAt":"2026-08-14T10:00:00.000Z"}""")
        )

        showScreen()

        compose.awaitText("мы на связи")
        compose.onNodeWithText("мы на связи", substring = true).assertIsDisplayed()
    }

    @Test
    fun отправка_добавляет_сообщение_и_стримит_ответ() {
        server.enqueue(register())
        server.enqueue(history())
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
        server.enqueue(register())
        server.enqueue(history())
        server.enqueue(
            sse("data: {\"choices\":[{\"delta\":{\"content\":\"x\"}}]}\n\n")
                .setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY)
        )
        server.enqueue(history())

        showScreen()
        compose.waitForIdle()

        compose.onNodeWithText(str(R.string.meerbot_input_hint)).performTextInput("привет")
        compose.onNodeWithContentDescription(str(R.string.meerbot_send)).performClick()

        compose.awaitText(str(R.string.meerbot_retry))
        compose.onNodeWithText(str(R.string.meerbot_not_delivered)).assertIsDisplayed()
    }

    @Test
    fun закрытый_диалог_блокирует_ввод() {
        server.enqueue(register())
        server.enqueue(history(mode = "closed"))

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
