package ru.meerbot.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.meerbot.sdk.network.StoreErrorReporter
import java.io.File

/**
 * Выход до `configure` на диске. Два экземпляра над одним каталогом — это два процесса
 * приложения: `SharedPreferences` каждого держали бы свой кэш, файл читается заново.
 */
class EarlyLogoutFileTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val errors = mutableListOf<String>()
    private val reporter = StoreErrorReporter { code, _ -> errors += code }

    private fun store(dir: File = folder.root) = EarlyLogoutFile({ dir }, reporter)

    @Test
    fun `отметка читается и снимается своим маркером`() {
        val file = store()
        val mark = file.mark()!!

        assertEquals(mark.marker, file.read())
        file.clear("чужой")
        assertEquals(mark.marker, file.read())
        file.clear(mark.marker)
        assertNull(file.read())
        assertTrue(errors.isEmpty())
    }

    /**
     * Процесс A применил выход и снял отметку; процесс B настраивает SDK позже. Кэш prefs у B
     * вернул бы старый флаг, и выход лёг бы поверх пользователя, вошедшего в A.
     */
    @Test
    fun `снятая другим процессом отметка не видна второму`() {
        val a = store()
        val b = store()
        val mark = a.mark()!!
        assertEquals(mark.marker, b.read())

        a.clear(mark.marker)

        assertNull(b.read())
    }

    /** Отметка, записанная в полёте другим вызовом или процессом, переживает снятие чужой. */
    @Test
    fun `снимается только своя отметка`() {
        val a = store()
        val b = store()
        val first = a.mark()!!
        val second = b.mark()!!

        a.clear(first.marker)

        assertEquals(second.marker, a.read())
    }

    @Test
    fun `сброс делает прежние отметки недействительными`() {
        val file = store()
        val mark = file.mark()!!

        file.clearAll()

        assertFalse(file.isIntact(mark))
        assertNull(file.read())
        assertTrue(file.isIntact(file.mark()!!))
    }

    /** Хост отключил инициализатор: контекста нет, писать некуда — без исключения. */
    @Test
    fun `без каталога отметки нет, и это не падение`() {
        val file = EarlyLogoutFile({ null }, reporter)

        assertNull(file.mark())
        assertNull(file.read())
        file.clear("x")
        file.clearAll()
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `незаписанная отметка названа в логе`() {
        val notADirectory = folder.newFile("occupied")

        assertNull(store(notADirectory).mark())
        assertEquals(listOf("early_logout_write_failed"), errors)
    }

    @Test
    fun `временный файл не остаётся после записи`() {
        store().mark()

        assertNotNull(File(folder.root, "meerbot_sdk_logout").takeIf { it.exists() })
        assertEquals(listOf("meerbot_sdk_logout"), folder.root.list()!!.toList())
    }
}
