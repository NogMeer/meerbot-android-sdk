package ru.meerbot.sdk.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Слияние серверной страницы в ленту — фундамент догона: SDK опрашивает
 * `GET /mobile/messages?since=` пока экран открыт, и одна и та же страница неизбежно приходит
 * повторно. Ошибка здесь видна пользователю сразу — дублем своего же сообщения.
 *
 * Зеркало iOS `ChatStoreMergeTests`.
 */
class ChatStoreMergeTest {

    private fun serverMessage(id: Long, role: String = "assistant", text: String) = ChatMessage(
        role = role,
        author = if (role == "assistant") "manager" else null,
        authorName = if (role == "assistant") "Роман" else null,
        content = text,
        serverId = id,
    )

    @Test
    fun `повторная страница ничего не меняет`() {
        val store = ChatStore()
        val page = listOf(serverMessage(10, text = "уже смотрю"))

        assertEquals(1, store.mergeServerMessages(page))
        assertEquals(0, store.mergeServerMessages(page))
        assertEquals(1, store.messages.size)
    }

    /** Своё сообщение приходит с сервера с id — оно обязано промоутиться, а не удвоиться. */
    @Test
    fun `локальное сообщение промоутится в серверное`() {
        val store = ChatStore()
        val local = store.appendUserMessage("не приходит письмо")
        store.setFailed(local.id, true)

        val added = store.mergeServerMessages(
            listOf(serverMessage(7, role = "user", text = "не приходит письмо")),
        )

        assertEquals(0, added)
        assertEquals(1, store.messages.size)
        assertEquals(7L, store.messages[0].serverId)
        assertFalse(store.messages[0].failed)
        assertEquals(local.id, store.messages[0].id)
    }

    @Test
    fun `ответ менеджера добавляется в конец ленты`() {
        val store = ChatStore()
        store.appendUserMessage("позови человека")

        store.mergeServerMessages(listOf(serverMessage(11, text = "я тут")))

        assertEquals(2, store.messages.size)
        assertEquals("manager", store.messages.last().author)
        assertEquals("Роман", store.messages.last().authorName)
    }

    @Test
    fun `курсор растёт монотонно и не откатывается старой страницей`() {
        val store = ChatStore()

        store.mergeServerMessages(listOf(serverMessage(10, text = "a"), serverMessage(12, text = "b")))
        assertEquals(12L, store.lastServerMessageId)

        store.mergeServerMessages(listOf(serverMessage(5, text = "старое")))
        assertEquals(12L, store.lastServerMessageId)
    }

    /** Иначе догон вечно перезапрашивал бы одни и те же строки: курсор не сдвинулся бы. */
    @Test
    fun `курсор двигается даже если вся страница пропущена`() {
        val store = ChatStore()
        val page = listOf(serverMessage(20, text = "уже есть"))
        store.mergeServerMessages(page)

        store.mergeServerMessages(page)

        assertEquals(20L, store.lastServerMessageId)
    }

    @Test
    fun `replaceAll поднимает курсор`() {
        val store = ChatStore()

        store.replaceAll(listOf(serverMessage(3, text = "a"), serverMessage(9, text = "b")))

        assertEquals(9L, store.lastServerMessageId)
    }

    @Test
    fun `выход сбрасывает курсор`() {
        val store = ChatStore()
        store.mergeServerMessages(listOf(serverMessage(42, text = "a")))

        store.resetForLogout()

        assertEquals(0L, store.lastServerMessageId)
        assertTrue(store.messages.isEmpty())
    }
}
