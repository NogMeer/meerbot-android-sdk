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

    /** Строка «вчерашней» истории — заведомо старше всего, что появилось на устройстве. */
    private val yesterday = System.currentTimeMillis() - 24 * 60 * 60 * 1000L

    private fun serverMessage(
        id: Long,
        role: String = "assistant",
        text: String,
        at: Long = System.currentTimeMillis(),
    ) = ChatMessage(
        role = role,
        author = if (role == "assistant") "manager" else null,
        authorName = if (role == "assistant") "Роман" else null,
        content = text,
        timestamp = at,
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

    // ─── Неподтверждённые сообщения и полная история ─────────────────────────────────────

    /** Стартовая история пришла после отправки: её строки старше ждущего сообщения. */
    @Test
    fun `страница старее ждущего сообщения встаёт перед ним`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        val placeholder = store.appendAssistantPlaceholder()

        store.mergeServerMessages(
            listOf(
                serverMessage(5, role = "user", text = "старый вопрос", at = yesterday),
                serverMessage(6, text = "старый ответ", at = yesterday + 1_000),
            ),
        )

        assertEquals(listOf("старый вопрос", "старый ответ", "привет", ""), store.messages.map { it.content })
        assertEquals(local.id, store.messages[2].id)
        assertEquals(placeholder.id, store.messages[3].id)
        assertTrue(store.messages[3].streaming)
        assertEquals(6L, store.lastServerMessageId)
    }

    @Test
    fun `история не снимает пометку недоставленного`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        store.setFailed(local.id, true)
        store.setRetryable("привет")

        store.mergeServerMessages(listOf(serverMessage(6, text = "старый ответ", at = yesterday)))

        assertEquals(listOf(6L, null), store.messages.map { it.serverId })
        assertTrue(store.messages[1].failed)
        assertEquals("привет", store.state.value.retryable)
    }

    /**
     * Стримящийся пузырь ещё дописывается: промоут снял бы `streaming`, а серверная строка с
     * тем же текстом — не его окончательная версия.
     */
    @Test
    fun `стримящийся ответ не промоутится`() {
        val store = ChatStore()
        val placeholder = store.appendAssistantPlaceholder()
        store.updateAssistantContent(placeholder.id, "Готово")

        store.mergeServerMessages(listOf(serverMessage(12, text = "Готово")))

        val bubble = store.messages.single { it.id == placeholder.id }
        assertTrue(bubble.streaming)
        assertNull(bubble.serverId)
        assertEquals(2, store.messages.size)
    }

    /**
     * Пользователь повторил вчерашний текст. Эхо — самая новая строка с этим текстом; вчерашняя
     * не должна «съесть» ждущее сообщение.
     */
    @Test
    fun `эхо забирает самая новая строка с тем же текстом`() {
        val store = ChatStore()
        val local = store.appendUserMessage("ок")

        store.mergeServerMessages(
            listOf(
                serverMessage(3, role = "user", text = "ок", at = yesterday),
                serverMessage(4, text = "принято", at = yesterday + 1_000),
                serverMessage(9, role = "user", text = "ок"),
            ),
        )

        assertEquals(listOf(3L, 4L, 9L), store.messages.map { it.serverId })
        assertEquals(local.id, store.messages[2].id)
    }

    /**
     * Пользователь написал «да» до прихода стартовой истории, а в истории — вчерашнее «да» с
     * ответом. До правки вчерашняя строка забирала новое сообщение: сама пропадала из ленты,
     * обрыв засчитывался доставкой по её id, а настоящее эхо вставало вторым «да».
     */
    @Test
    fun `вчерашнее сообщение с тем же текстом не забирает неотправленное`() {
        val store = ChatStore()
        val local = store.appendUserMessage("да")

        store.mergeServerMessages(
            listOf(
                serverMessage(5, role = "user", text = "да", at = yesterday),
                serverMessage(6, text = "Хорошо", at = yesterday + 1_000),
            ),
        )

        assertEquals(listOf(5L, 6L, null), store.messages.map { it.serverId })
        assertEquals(local.id, store.messages[2].id)

        store.mergeServerMessages(listOf(serverMessage(40, role = "user", text = "да")))

        assertEquals(listOf(5L, 6L, 40L), store.messages.map { it.serverId })
        assertEquals(local.id, store.messages[2].id)
        assertEquals(2, store.messages.count { it.content == "да" })
    }

    /**
     * Курсор на момент отправки известен: эхо — только строка новее него, даже если по времени
     * старая строка «свежая» (догон принёс её позже, чем курсор ушёл вперёд).
     */
    @Test
    fun `строка не новее курсора на момент отправки не эхо`() {
        val store = ChatStore()
        store.mergeServerMessages(listOf(serverMessage(7, text = "ответ")))
        val local = store.appendUserMessage("да")

        store.mergeServerMessages(listOf(serverMessage(6, role = "user", text = "да")))
        assertNull(store.messages.single { it.id == local.id }.serverId)

        store.mergeServerMessages(listOf(serverMessage(8, role = "user", text = "да")))
        assertEquals(8L, store.messages.single { it.id == local.id }.serverId)
    }

    @Test
    fun `повтор сдвигает порог эха к курсору на момент повтора`() {
        val store = ChatStore()
        val local = store.appendUserMessage("да")
        store.setFailed(local.id, true)
        store.mergeServerMessages(listOf(serverMessage(9, text = "другое")))

        store.markResent(local.id)
        store.mergeServerMessages(listOf(serverMessage(8, role = "user", text = "да")))

        assertNull(store.messages.single { it.id == local.id }.serverId)
    }

    /** Сообщение дошло — «Повторить» отправил бы его второй раз. */
    @Test
    fun `эхо недоставленного снимает текст повтора`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        store.setFailed(local.id, true)
        store.setRetryable("привет")

        store.mergeServerMessages(listOf(serverMessage(7, role = "user", text = "привет")))

        assertFalse(store.messages.single().failed)
        assertNull(store.state.value.retryable)
    }

    /**
     * Два недоставленных, дошло новое. Сверка по тексту снимала «Повторить» целиком, и старое
     * сообщение оставалось недоставленным без кнопки. Теперь повтор переходит к нему (как iOS).
     */
    @Test
    fun `эхо нового из двух недоставленных оставляет повтор старому`() {
        val store = ChatStore()
        val older = store.appendUserMessage("первое")
        store.setFailed(older.id, true)
        val newer = store.appendUserMessage("второе")
        store.setFailed(newer.id, true)
        store.setRetryable("второе")

        store.mergeServerMessages(listOf(serverMessage(7, role = "user", text = "второе")))

        assertTrue(store.messages.single { it.id == older.id }.failed)
        assertFalse(store.messages.single { it.id == newer.id }.failed)
        assertEquals("первое", store.state.value.retryable)
    }

    /** Слияние «Повторить» не выдумывает: без текста повтора его нет и после. */
    @Test
    fun `слияние не ставит повтор, которого не было`() {
        val store = ChatStore()
        val local = store.appendUserMessage("привет")
        store.setFailed(local.id, true)

        store.mergeServerMessages(listOf(serverMessage(6, text = "старый ответ", at = yesterday)))

        assertNull(store.state.value.retryable)
    }

    /** Два одинаковых неотправленных сообщения: эхо достаётся каждому по порядку, без дублей. */
    @Test
    fun `одинаковые сообщения промоутятся по порядку`() {
        val store = ChatStore()
        val first = store.appendUserMessage("да")
        val second = store.appendUserMessage("да")

        store.mergeServerMessages(
            listOf(serverMessage(7, role = "user", text = "да"), serverMessage(8, role = "user", text = "да")),
        )

        assertEquals(listOf(first.id, second.id), store.messages.map { it.id })
        assertEquals(listOf(7L, 8L), store.messages.map { it.serverId })
    }

    /**
     * Порядок между серверными строками — по серверному id, даже если страница пришла после
     * неподтверждённого сообщения, которое старше части из них.
     */
    @Test
    fun `серверные строки встают по id вокруг недоставленного`() {
        val store = ChatStore()
        store.mergeServerMessages(listOf(serverMessage(6, text = "строка 6", at = yesterday)))
        val failed = store.appendUserMessage("не ушло")
        store.setFailed(failed.id, true)
        store.mergeServerMessages(listOf(serverMessage(9, text = "строка 9")))

        store.mergeServerMessages(
            listOf(
                serverMessage(5, text = "строка 5", at = yesterday - 1_000),
                serverMessage(6, text = "строка 6", at = yesterday),
                serverMessage(9, text = "строка 9"),
                serverMessage(10, text = "строка 10"),
            ),
        )

        assertEquals(listOf(5L, 6L, null, 9L, 10L), store.messages.map { it.serverId })
        assertEquals(failed.id, store.messages[2].id)
    }

    @Test
    fun `сброс identity стирает неподтверждённые сообщения`() {
        val store = ChatStore()
        store.mergeServerMessages(listOf(serverMessage(6, text = "a")))
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
