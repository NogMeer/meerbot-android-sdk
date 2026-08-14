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
    fun `битый JSON не роняет разбор`() {
        val result = event("meta", "{не json")
        assertEquals(ChatStreamEvent.Meta(-1L, ChatMode.Ai), result)
    }
}
