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
 * Запись «пользователь вышел, а SDK ещё не настроен». Содержимое — случайный маркер отметки.
 *
 * Почему файл, а не prefs:
 * - **Чтение всегда с диска.** `SharedPreferences` кэшируют файл в памяти процесса: второй
 *   процесс приложения (свой `:remote`-сервис, где тоже зовут `configure`) видел бы флаг, уже
 *   снятый первым, и повторил бы выход поверх пользователя, успевшего войти.
 * - **`noBackupFilesDir`.** Каталог не попадает в Auto Backup и перенос на новое устройство:
 *   восстановленная копия не принесёт давно применённый выход на свежую установку.
 * - Отдельно от prefs SDK: `meerbot_sdk` служит запасным хранилищем при недоступном Keystore.
 *
 * Маркер нужен, чтобы снять ровно ту отметку, что применили: отметка, записанная в полёте
 * (другим вызовом или другим процессом), остаётся и применяется своим чередом.
 *
 * С установкой отметка не связывается сознательно: до `configure` идентификатор установки
 * лежит в зашифрованных prefs, и прочитать его значило бы открывать Keystore на пути
 * `identify(null)`. Цена — выход применится и к установке, сменившейся после отметки (сброс
 * хранилища прошивкой). Такой установке рвать нечего: её устройство на сервере новое.
 *
 * Пределы при нескольких процессах: чтение, применение и снятие не атомарны между процессами.
 * Два процесса, настраивающие SDK одновременно, могут применить одну отметку оба (второй выход
 * безвреден, пока в этом окне никто не вошёл). Чат SDK рассчитан на один процесс приложения.
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

    /** Маркер отметки на диске или `null`. Читается файл, а не кэш. */
    fun read(): String? {
        val file = File(directory() ?: return null, FILE_NAME)
        return try {
            if (!file.exists()) null else file.readText().trim().ifEmpty { null }
        } catch (e: IOException) {
            onError.report("early_logout_read_failed", e)
            null
        }
    }

    /** Снять отметку, только если на диске всё ещё [marker]. */
    fun clear(marker: String) {
        synchronized(lock) {
            if (read() == marker) delete()
        }
    }

    /** Снять любую отметку (`reset()`): отметки, выданные раньше, больше не действуют. */
    fun clearAll() {
        synchronized(lock) {
            resets.incrementAndGet()
            delete()
        }
    }

    private fun delete() {
        val file = File(directory() ?: return, FILE_NAME)
        if (file.exists() && !file.delete()) {
            onError.report("early_logout_clear_failed", IOException("delete() returned false"))
        }
    }

    /** Временный файл + `fsync` + переименование: оборванная запись не оставит пустую отметку. */
    private fun writeAtomically(dir: File, marker: String) {
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("no directory: $dir")
        val tmp = File(dir, "$FILE_NAME.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(marker.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(File(dir, FILE_NAME))) {
            tmp.delete()
            throw IOException("rename failed")
        }
    }

    private companion object {
        const val FILE_NAME = "meerbot_sdk_logout"
    }
}
