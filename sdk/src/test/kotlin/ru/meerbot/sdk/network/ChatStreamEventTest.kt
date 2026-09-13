package ru.meerbot.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.meerbot.sdk.state.ChatMode

/** Отображение сырого SSE в типизированные события. */
class ChatStreamEventTest {

    private fun event(name: String, data: String) = ChatStreamEvent.from(SseEvent(name, data))

    @Test
    fun `чанк генерации превращается в дельту`() {
        val result = event("message", "{\"choices\":[{\"delta\":{\"content\":\"При\"}}]}")
        assertEquals(ChatStreamEvent.ContentDelta("При"), result)
    }

    @Test
    fun `пустой чанк пропускается`() {
        assertNull(event("message", "{\"choices\":[{\"delta\":{}}]}"))
    }

    @Test
    fun `DONE завершает генерацию`() {
        assertEquals(ChatStreamEvent.Done, event("message", "[DONE]"))
    }

    @Test
    fun `meta отдаёт диалог и режим`() {
        val result = event("meta", "{\"conversationId\":42,\"mode\":\"human\",\"escalation\":true}")
        assertEquals(ChatStreamEvent.Meta(42L, ChatMode.Human), result)
    }

    @Test
    fun `неизвестный режим падает в ai`() {
        val result = event("meta", "{\"conversationId\":1,\"mode\":\"нечто\"}")
        assertEquals(ChatMode.Ai, (result as ChatStreamEvent.Meta).mode)
    }

    @Test
    fun `ответ менеджера разбирается`() {
        val result = event(
            "manager_message",
            "{\"messageId\":9,\"text\":\"Уже смотрю\",\"authorName\":\"Марат\"}",
        )
        assertEquals(
            ChatStreamEvent.Manager(ManagerMessage(9L, "Уже смотрю", "Марат")),
            result,
        )
    }

    @Test
    fun `ответ менеджера без текста пропускается`() {
        assertNull(event("manager_message", "{\"messageId\":9}"))
    }

    @Test
    fun `forwarded_to_manager без режима считается ожиданием менеджера`() {
        val result = event("forwarded_to_manager", "{}")
        assertEquals(
            ChatStreamEvent.ForwardedToManager(ChatMode.PendingEscalation),
            result,
        )
    }

    @Test
    fun `error несёт машинный код`() {
        val result = event("error", "{\"code\":\"ai_unavailable\",\"message\":\"нет ИИ\"}")
        assertEquals(ChatStreamEvent.ServerError("ai_unavailable", "нет ИИ"), result)
    }

    @Test
    fun `heartbeat и timeout распознаются`() {
        assertEquals(ChatStreamEvent.Heartbeat, event("heartbeat", "{}"))
        assertEquals(ChatStreamEvent.Timeout, event("timeout", "{}"))
    }

    @Test
    fun `shutdown несёт причину`() {
        assertEquals(
            ChatStreamEvent.Shutdown("server_restart"),
            event("shutdown", "{}"),
        )
    }

    @Test
    fun `незнакомое событие не ошибка`() {
        // `usage` сервер шлёт хелп-виджету кабинета; мобильному клиенту оно не адресовано,
        // но ломать его не должно.
        val result = event("usage", "{\"freeRemaining\":3}")
        assertTrue(result is ChatStreamEvent.Unknown)
        assertEquals("usage", (result as ChatStreamEvent.Unknown).name)
    }

    @Test
    fun `meta несёт подтверждение приёма отправки`() {
        val result = event(
            "meta",
            "{\"conversationId\":42,\"mode\":\"ai\",\"clientMessageId\":\"A1B2C3D4-1111-4222-8333-444455556666\"," +
                "\"userMessageId\":501,\"replayed\":true}",
        ) as ChatStreamEvent.Meta

        // Id приводится к нижнему регистру: сервер хранит его так же, и сверка со строкой ленты
        // не должна зависеть от регистра, в котором его вернули.
        assertEquals("a1b2c3d4-1111-4222-8333-444455556666", result.clientMessageId)
        assertEquals(501L, result.userMessageId)
        assertTrue(result.replayed)
    }

    /** Старый сервер полей не знает: прежнее правило (сверка эха по тексту) обязано сохраниться. */
    @Test
    fun `meta без подтверждения оставляет поля пустыми`() {
        val result = event("meta", "{\"conversationId\":42,\"mode\":\"ai\"}") as ChatStreamEvent.Meta

        assertNull(result.clientMessageId)
        assertNull(result.userMessageId)
        assertEquals(false, result.replayed)
    }

    /** Половина подтверждения ничего не подтверждает: строка ленты осталась бы без серверного id. */
    @Test
    fun `meta с id без серверного номера подтверждением не считается`() {
        val result = event(
            "meta",
            "{\"conversationId\":42,\"mode\":\"ai\",\"clientMessageId\":\"a1b2c3d4-1111-4222-8333-444455556666\"," +
                "\"userMessageId\":null}",
        ) as ChatStreamEvent.Meta

        assertNull(result.clientMessageId)
        assertNull(result.userMessageId)
    }

    /**
     * Поля подтверждения — в теле класса: приложение, собранное против 0.2.8, сравнивает `Meta`
     * прежним `equals`, и обновление SDK не имеет права его поменять.
     */
    @Test
    fun `подтверждение не участвует в equals`() {
        val withAcceptance = event(
            "meta",
            "{\"conversationId\":42,\"mode\":\"ai\",\"clientMessageId\":\"a1b2c3d4-1111-4222-8333-444455556666\"," +
                "\"userMessageId\":501}",
        )

        assertEquals(ChatStreamEvent.Meta(42L, ChatMode.Ai), withAcceptance)
    }

    @Test
    fun `битый JSON не роняет разбор`() {
        val result = event("meta", "{не json")
        assertEquals(ChatStreamEvent.Meta(-1L, ChatMode.Ai), result)
    }
}
