package ru.meerbot.sdk.internal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import ru.meerbot.sdk.network.StoreErrorReporter
import java.io.File
import java.util.UUID

/**
 * Выход до `configure` на диске. Два экземпляра над одним каталогом — это два процесса
 * приложения: `SharedPreferences` каждого держали бы свой кэш, каталог читается заново.
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

        assertEquals(listOf(mark.marker), file.read())
        file.clear("чужой")
        file.clear(UUID.randomUUID().toString())
        assertEquals(listOf(mark.marker), file.read())
        file.clear(mark.marker)
        assertTrue(file.read().isEmpty())
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
        assertEquals(listOf(mark.marker), b.read())

        a.clear(mark.marker)

        assertTrue(b.read().isEmpty())
    }

    /**
     * Два процесса пишут отметки одновременно: у каждой свой файл (и свой временный файл), и
     * снятие одной не трогает другую. С одним общим файлом вторая запись затирала первую, а
     * снятие «прочитать и удалить» могло удалить соседа, записанного между чтением и удалением.
     */
    @Test
    fun `снимается только своя отметка, выход ждёт, пока есть хоть одна`() {
        val a = store()
        val b = store()
        val first = a.mark()!!
        val second = b.mark()!!

        assertEquals(listOf(first.marker, second.marker).sorted(), a.read())
        assertEquals(
            listOf(first.marker, second.marker).map { "meerbot_sdk_logout.$it" }.sorted(),
            folder.root.list()!!.sorted(),
        )

        a.clear(first.marker)

        assertEquals(listOf(second.marker), b.read())
    }

    @Test
    fun `сброс делает прежние отметки недействительными`() {
        val file = store()
        val mark = file.mark()!!
        store().mark()
        // Временный файл записи, оборванной убийством процесса.
        File(folder.root, "meerbot_sdk_logout.${UUID.randomUUID()}.tmp").writeText("x")

        file.clearAll()

        assertFalse(file.isIntact(mark))
        assertTrue(file.read().isEmpty())
        assertTrue(folder.root.list()!!.isEmpty())
        assertTrue(file.isIntact(file.mark()!!))
    }

    @Test
    fun `временные и посторонние файлы отметкой не считаются`() {
        File(folder.root, "meerbot_sdk_logout.${UUID.randomUUID()}.tmp").writeText("x")
        File(folder.root, "meerbot_sdk_logout.garbage").writeText("x")
        File(folder.root, "meerbot_sdk_logout").writeText(UUID.randomUUID().toString())

        assertTrue(store().read().isEmpty())
    }

    /** Маркер попадает в имя файла: собрать из него путь за пределы каталога нельзя. */
    @Test
    fun `маркер с путём ничего не удаляет`() {
        val sdkDir = folder.newFolder("sdk")
        File(sdkDir, "meerbot_sdk_logout..").mkdir()
        val victim = folder.newFile("victim")

        store(sdkDir).clear("./../../victim")

        assertTrue(victim.exists())
    }

    /** Хост отключил инициализатор: контекста нет, писать некуда — без исключения. */
    @Test
    fun `без каталога отметки нет, и это не падение`() {
        val file = EarlyLogoutFile({ null }, reporter)

        assertNull(file.mark())
        assertTrue(file.read().isEmpty())
        file.clear(UUID.randomUUID().toString())
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
        val mark = store().mark()!!

        assertEquals(listOf("meerbot_sdk_logout.${mark.marker}"), folder.root.list()!!.toList())
    }
}
