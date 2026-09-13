package ru.meerbot.sdk.internal

import android.annotation.SuppressLint
import android.content.Context
import androidx.startup.Initializer
import ru.meerbot.sdk.network.StoreErrorReporter
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * Даёт SDK контекст приложения до `MeerBot.configure(...)` через `androidx.startup`.
 *
 * Нужен ровно для одного: выход, запрошенный до настройки, обязан пережить процесс. Хост,
 * настраивающий SDK лениво (на открытии чата), вызывает `identify(null)` на экране выхода,
 * когда `configure` ещё не было; без диска сигнал умер бы с процессом, и следующий человек на
 * телефоне открыл бы тред прежнего.
 *
 * Инициализатор, а не свой `ContentProvider`: провайдер `androidx.startup` у хоста уже есть
 * (его приносят Compose и lifecycle), и лишний компонент в манифесте с лишним `onCreate` на
 * старте процесса не нужен. В `create` только запоминается контекст — диск не трогается.
 *
 * Хост, отключивший инициализатор (`tools:node="remove"` на `InitializationProvider` или на
 * этой записи `meta-data`), теряет одно: выход до `configure` живёт только в памяти процесса,
 * а в лог уходит `logout_not_persisted`. После `configure` контекст у SDK есть в любом случае.
 *
 * Только основной процесс: провайдер без `android:process` создаётся лишь в нём. В другом
 * процессе приложения (`:remote`) инициализатор не запускается, и выход до первого `configure`
 * в этом процессе тоже живёт только в памяти (`logout_not_persisted`).
 */
internal class MeerBotInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        EarlyLogout.attach(context)
    }

    override fun dependencies(): List<Class<out Initializer<*>>> = emptyList()
}

/**
 * Выход, запрошенный до `configure(...)`, на диске (см. [EarlyLogoutFile]).
 *
 * Держит только контекст ПРИЛОЖЕНИЯ (`attach` берёт `applicationContext`): он живёт столько же,
 * сколько процесс, утечки активити здесь нет — как и у `MeerBot`.
 */
@SuppressLint("StaticFieldLeak")
internal object EarlyLogout {
    @Volatile
    private var context: Context? = null

    val file = EarlyLogoutFile(
        // Каталог открывается лениво — в первой записи или чтении, не в инициализаторе.
        directory = { context?.noBackupFilesDir },
        onError = StoreErrorReporter.Logcat,
    )

    fun attach(context: Context) {
        if (this.context == null) this.context = context.applicationContext
    }
}

/**
 * Отметки «пользователь вышел, а SDK ещё не настроен»: по файлу на отметку,
 * `meerbot_sdk_logout.<маркер>`, где маркер — случайный UUID. Выход ждёт применения, пока есть
 * хоть одна отметка.
 *
 * Почему файлы, а не prefs:
 * - **Чтение всегда с диска.** `SharedPreferences` кэшируют файл в памяти процесса: второй
 *   процесс приложения, где SDK настраивается, видел бы флаг, уже снятый первым, и повторил бы
 *   выход поверх пользователя, успевшего войти.
 * - **`noBackupFilesDir`.** Каталог не попадает в Auto Backup и перенос на новое устройство:
 *   восстановленная копия не принесёт давно применённый выход на свежую установку.
 * - Отдельно от prefs SDK: `meerbot_sdk` служит запасным хранилищем при недоступном Keystore.
 *
 * Почему файл на отметку: запись и снятие не пересекаются между процессами. Отметка пишется
 * во временный файл со своим маркером в имени и переименовывается; снятие удаляет ровно свой
 * файл, ничего не читая. С одним общим файлом два процесса затирали бы общий временный файл
 * друг друга, а снятие «прочитать и удалить, если маркер мой» могло удалить отметку соседа,
 * записанную между чтением и удалением.
 *
 * С установкой отметка не связывается сознательно: до `configure` идентификатор установки
 * лежит в зашифрованных prefs, и прочитать его значило бы открывать Keystore на пути
 * `identify(null)`. Цена — выход применится и к установке, сменившейся после отметки (сброс
 * хранилища прошивкой). Такой установке рвать нечего: её устройство на сервере новое.
 *
 * Пределы при нескольких процессах: чтение, применение и снятие не атомарны между процессами.
 * Два процесса, настраивающие SDK одновременно, могут применить одну отметку оба (второй выход
 * безвреден, пока в этом окне никто не вошёл). Чат SDK рассчитан на один процесс приложения.
 * В процессе, где не отработал инициализатор (`:remote`), отметку до `configure` записать
 * некуда — см. [MeerBotInitializer].
 */
internal class EarlyLogoutFile(
    private val directory: () -> File?,
    private val onError: StoreErrorReporter,
) {
    /** Отметка этого процесса: маркер и эпоха [clearAll] на момент записи. */
    class Mark(val marker: String, internal val resetEpoch: Int)

    private val lock = Any()
    private val resets = AtomicInteger()

    /**
     * Записать отметку синхронно, с `fsync`. `null` — записать некуда (нет контекста) или
     * запись не удалась (`early_logout_write_failed` в логе).
     */
    fun mark(): Mark? {
        val dir = directory() ?: return null
        val marker = UUID.randomUUID().toString()
        synchronized(lock) {
            val epoch = resets.get()
            return try {
                writeAtomically(dir, marker)
                Mark(marker, epoch)
            } catch (e: IOException) {
                onError.report("early_logout_write_failed", e)
                null
            }
        }
    }

    /** `false` — после отметки был [clearAll] (`reset()`), и её на диске уже нет. */
    fun isIntact(mark: Mark): Boolean = mark.resetEpoch == resets.get()

    /** Маркеры всех отметок на диске, по возрастанию. Читается каталог, а не кэш. */
    fun read(): List<String> {
        val dir = directory() ?: return emptyList()
        if (!dir.isDirectory) return emptyList()
        val names = dir.list() ?: run {
            onError.report("early_logout_read_failed", IOException("list() returned null: $dir"))
            return emptyList()
        }
        return names.mapNotNull(::markerOf).sorted()
    }

    /** Снять отметку [marker] — удалить ровно её файл. Чужие отметки не трогаются. */
    fun clear(marker: String) {
        if (!isMarker(marker)) return
        val dir = directory() ?: return
        delete(File(dir, PREFIX + marker))
    }

    /** Снять все отметки (`reset()`): отметки, выданные раньше, больше не действуют. */
    fun clearAll() {
        synchronized(lock) {
            resets.incrementAndGet()
            val dir = directory() ?: return
            // Вместе с отметками — временные файлы записей, оборванных убийством процесса.
            dir.listFiles { file -> file.name.startsWith(PREFIX) }?.forEach(::delete)
        }
    }

    private fun delete(file: File) {
        if (file.exists() && !file.delete() && file.exists()) {
            onError.report("early_logout_clear_failed", IOException("delete() returned false: ${file.name}"))
        }
    }

    /**
     * Временный файл со своим маркером + `fsync` + переименование: оборванная запись не
     * оставит отметку, а параллельная запись другого процесса — свой временный файл.
     */
    private fun writeAtomically(dir: File, marker: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no directory: $dir")
        val tmp = File(dir, PREFIX + marker + TMP_SUFFIX)
        FileOutputStream(tmp).use { out ->
            out.write(marker.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, PREFIX + marker))) {
            tmp.delete()
            throw IOException("rename failed")
        }
    }

    /** Маркер из имени файла отметки; временные и посторонние файлы — `null`. */
    private fun markerOf(name: String): String? =
        name.takeIf { it.startsWith(PREFIX) }?.removePrefix(PREFIX)?.takeIf(::isMarker)

    /** Только канонический UUID: маркер попадает в имя файла, путь из него не собрать. */
    private fun isMarker(value: String): Boolean =
        runCatching { UUID.fromString(value).toString() == value }.getOrDefault(false)

    private companion object {
        const val PREFIX = "meerbot_sdk_logout."
        const val TMP_SUFFIX = ".tmp"
    }
}
