package ru.meerbot.sdk.state

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /**
     * Регрессия: поток начинается с перевода строки (модель стабильно так отвечает на
     * передачу менеджеру), сервер хранит строку подрезанной. До правки слияние не узнавало
     * свой же ответ и клало серверную копию рядом — пользователь видел сообщение дважды.
     */
    @Test
    fun `ответ с переводом строки в начале потока не двоится`() {
        val store = ChatStore()
        val placeholder = store.appendAssistantPlaceholder()
        store.updateAssistantContent(placeholder.id, "\n")
        store.updateAssistantContent(placeholder.id, "Понимаю, сейчас подключу менеджера.")
        store.finalizeAssistant(placeholder.id)

        val added = store.mergeServerMessages(
            listOf(serverMessage(11, text = "Понимаю, сейчас подключу менеджера.")),
        )

        assertEquals(0, added)
        assertEquals(1, store.messages.size)
        assertEquals(11L, store.messages.single().serverId)
        assertEquals("Понимаю, сейчас подключу менеджера.", store.messages.single().content)
    }

    /** Хвостовые пробелы потока тоже не должны мешать слиянию. */
    @Test
    fun `хвостовой перенос строки не мешает слиянию`() {
        val store = ChatStore()
        val placeholder = store.appendAssistantPlaceholder()
        store.updateAssistantContent(placeholder.id, "Готово")
        store.updateAssistantContent(placeholder.id, "\n\n")
        store.finalizeAssistant(placeholder.id)

        assertEquals(0, store.mergeServerMessages(listOf(serverMessage(12, text = "Готово"))))
        assertEquals(1, store.messages.size)
    }

    // ─── Хвост треда (старт, обрыв, рестарт сервера) ──────────────────────────────────────

    private fun snapshot(vararg ids: Long) = ids.map { serverMessage(it, text = "строка $it") }

    /** Человек написал до прихода стартовой истории: снимок о сообщении не знает. */
    @Test
    fun `снимок сохраняет отправляемое сообщение и стримящийся ответ`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        val placeholder = store.appendAssistantPlaceholder()

        store.mergeServerSnapshot(
            listOf(serverMessage(5, role = "user", text = "старый вопрос"), serverMessage(6, text = "старый ответ")),
        )

        assertEquals(listOf("старый вопрос", "старый ответ", "привет", ""), store.messages.map { it.content })
        assertEquals(local.id, store.messages[2].id)
        assertEquals(placeholder.id, store.messages[3].id)
        assertTrue(store.messages[3].streaming)
        assertEquals(6L, store.lastServerMessageId)
    }

    @Test
    fun `снимок не снимает пометку недоставленного`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        store.setFailed(local.id, true)
        store.setRetryable("привет")

        store.mergeServerSnapshot(snapshot(6))

        assertTrue(store.messages.single { it.id == local.id }.failed)
        assertEquals("привет", store.state.value.retryable)
    }

    @Test
    fun `эхо в снимке промоутит локальное сообщение без дубля`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        store.setFailed(local.id, true)

        store.mergeServerSnapshot(listOf(serverMessage(6, text = "старый ответ"), serverMessage(7, role = "user", text = "привет")))

        assertEquals(listOf(6L, 7L), store.messages.map { it.serverId })
        assertEquals(local.id, store.messages[1].id)
        assertFalse(store.messages[1].failed)
    }

    /** Два одинаковых неотправленных сообщения: эхо достаётся каждому по порядку, без дублей. */
    @Test
    fun `одинаковые сообщения промоутятся по порядку`() {
        val store = ChatStore()
        val first = store.appendUserMessage("да")
        val second = store.appendUserMessage("да")

        store.mergeServerSnapshot(listOf(serverMessage(7, role = "user", text = "да"), serverMessage(8, role = "user", text = "да")))

        assertEquals(listOf(first.id, second.id), store.messages.map { it.id })
        assertEquals(listOf(7L, 8L), store.messages.map { it.serverId })
    }

    /** «привет» вчера и «привет» сегодня: строка, которую лента уже видела, эхом не бывает. */
    @Test
    fun `строка снимка не новее курсора не промоутит локальное сообщение`() {
        val store = ChatStore()
        store.mergeServerMessages(listOf(serverMessage(3, role = "user", text = "привет")))
        val local = store.appendUserMessage("привет")

        store.mergeServerSnapshot(listOf(serverMessage(3, role = "user", text = "привет")))

        assertEquals(listOf(3L, null), store.messages.map { it.serverId })
        assertEquals(local.id, store.messages[1].id)
    }

    @Test
    fun `локальное сообщение остаётся между серверными строками, за которыми стояло`() {
        val store = ChatStore()
        store.mergeServerMessages(snapshot(6))
        val failed = store.appendUserMessage("не ушло")
        store.setFailed(failed.id, true)
        store.mergeServerMessages(snapshot(9))

        store.mergeServerSnapshot(snapshot(5, 6, 9, 10))

        assertEquals(listOf(5L, 6L, null, 9L, 10L), store.messages.map { it.serverId })
        assertEquals(failed.id, store.messages[2].id)
    }

    @Test
    fun `локальное сообщение без предшественника в снимке встаёт перед следующей строкой`() {
        val store = ChatStore()
        val local = store.appendUserMessage("не ушло")
        store.mergeServerMessages(snapshot(9))

        store.mergeServerSnapshot(snapshot(8, 9))

        assertEquals(listOf(8L, null, 9L), store.messages.map { it.serverId })
        assertEquals(local.id, store.messages[1].id)
    }

    @Test
    fun `обрывок ответа уходит, когда снимок принёс ответ целиком`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        val placeholder = store.appendAssistantPlaceholder()
        store.updateAssistantContent(placeholder.id, "Здрав")
        store.finalizeAssistant(placeholder.id)

        store.mergeServerSnapshot(
            listOf(serverMessage(1, role = "user", text = "привет"), serverMessage(2, text = "Здравствуйте")),
            supersededLocalId = placeholder.id,
        )

        assertEquals(listOf("привет", "Здравствуйте"), store.messages.map { it.content })
        assertEquals(local.id, store.messages[0].id)
    }

    /** Смена пользователя уносит и неотправленное: оно принадлежит прежнему. */
    @Test
    fun `смена пользователя уносит локальные сообщения`() {
        val store = ChatStore()
        store.mergeServerMessages(snapshot(6))
        val failed = store.appendUserMessage("не ушло")
        store.setFailed(failed.id, true)
        store.setRetryable("не ушло")
        store.appendUserMessage("отправляется")
        store.appendAssistantPlaceholder()

        store.resetForIdentityChange()

        assertTrue(store.messages.isEmpty())
        assertNull(store.state.value.retryable)
        assertEquals(0L, store.lastServerMessageId)
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
