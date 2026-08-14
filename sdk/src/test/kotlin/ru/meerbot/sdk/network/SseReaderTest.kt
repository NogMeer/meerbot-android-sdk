package ru.meerbot.sdk.network

import kotlinx.coroutines.runBlocking
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Разбор SSE. Проверяем ровно то, что приходит с нашего бэкенда, плюс формы, которые
 * спецификация разрешает и которые нас не должны ломать.
 */
class SseReaderTest {

    private fun parse(raw: String): List<SseEvent> = runBlocking {
        val events = mutableListOf<SseEvent>()
        SseReader(Buffer().writeUtf8(raw)).read { events += it }
        events
    }

    @Test
    fun `безымянное событие получает имя message`() {
        val events = parse("data: {\"a\":1}\n\n")
        assertEquals(1, events.size)
        assertEquals("message", events[0].name)
        assertEquals("{\"a\":1}", events[0].data)
    }

    @Test
    fun `именованное событие сохраняет имя`() {
        val events = parse("event: meta\ndata: {\"conversationId\":7}\n\n")
        assertEquals("meta", events[0].name)
        assertEquals("{\"conversationId\":7}", events[0].data)
    }

    @Test
    fun `несколько событий подряд`() {
        val events = parse(
            "event: meta\ndata: {}\n\n" +
                "data: chunk1\n\n" +
                "data: chunk2\n\n" +
                "data: [DONE]\n\n"
        )
        assertEquals(4, events.size)
        assertEquals(listOf("meta", "message", "message", "message"), events.map { it.name })
        assertEquals("[DONE]", events.last().data)
    }

    @Test
    fun `многострочный data склеивается переводом строки`() {
        val events = parse("data: первая\ndata: вторая\n\n")
        assertEquals("первая\nвторая", events[0].data)
    }

    @Test
    fun `CRLF как разделитель строк`() {
        val events = parse("event: heartbeat\r\ndata: {}\r\n\r\n")
        assertEquals(1, events.size)
        assertEquals("heartbeat", events[0].name)
    }

    @Test
    fun `комментарий keep-alive не создаёт события`() {
        val events = parse(": ping\n\ndata: x\n\n")
        assertEquals(1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test
    fun `поля id и retry игнорируются`() {
        val events = parse("id: 42\nretry: 3000\ndata: x\n\n")
        assertEquals(1, events.size)
        assertEquals("x", events[0].data)
    }

    @Test
    fun `незавершённый последний блок всё равно отдаётся`() {
        // Сервер закрыл соединение без завершающей пустой строки.
        val events = parse("event: meta\ndata: {\"conversationId\":1}")
        assertEquals(1, events.size)
        assertEquals("meta", events[0].name)
    }

    @Test
    fun `пустой поток не даёт событий`() {
        assertTrue(parse("").isEmpty())
    }

    @Test
    fun `кириллица не рвётся`() {
        val events = parse("data: Здравствуйте, чем помочь?\n\n")
        assertEquals("Здравствуйте, чем помочь?", events[0].data)
    }

    @Test
    fun `съедается ровно один пробел после двоеточия`() {
        val events = parse("data:  два пробела\n\n")
        assertEquals(" два пробела", events[0].data)
    }
}
