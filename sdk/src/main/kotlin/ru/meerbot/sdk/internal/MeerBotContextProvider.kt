package ru.meerbot.sdk.internal

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.Cursor
import android.net.Uri

/**
 * Даёт SDK контекст приложения до `MeerBot.configure(...)`. Данных не отдаёт, не экспортирован.
 *
 * Нужен ровно для одного: выход, запрошенный до настройки, обязан пережить процесс. Хост,
 * настраивающий SDK лениво (на открытии чата), вызывает `identify(null)` на экране выхода,
 * когда `configure` ещё не было; без диска сигнал умер бы с процессом, и следующий человек на
 * телефоне открыл бы тред прежнего. Тот же приём у Firebase и WorkManager.
 */
internal class MeerBotContextProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        EarlyLogout.attach(app)
        return true
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0
}

/**
 * Выход, запрошенный до `configure(...)`, на диске.
 *
 * Обычные prefs, а не зашифрованные: булево «был выход» — не персональные данные, а
 * `EncryptedSharedPreferences` пришлось бы открывать (Keystore, сотни миллисекунд) прямо в
 * `identify` на главном потоке. Отдельный файл, а не `meerbot_sdk`: тот служит запасным
 * хранилищем при недоступном Keystore, и снятие этого ключа стёрло бы там настоящий флаг.
 */
internal object EarlyLogout {
    private const val PREF_NAME = "meerbot_sdk_logout"
    private const val KEY_PENDING = "pending_logout"

    @Volatile
    private var prefs: SharedPreferences? = null

    /**
     * `getSharedPreferences` запускает чтение файла в фоне и сразу возвращается: к моменту
     * `identify` на главном потоке файл уже в памяти.
     */
    fun attach(context: Context) {
        if (prefs == null) {
            prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        }
    }

    /** `false` — контекста нет (хост вырезал провайдер из манифеста): сигнал только в памяти. */
    fun markPending(): Boolean {
        val store = prefs ?: return false
        store.edit().putBoolean(KEY_PENDING, true).apply()
        return true
    }

    fun isPending(): Boolean = prefs?.getBoolean(KEY_PENDING, false) == true

    fun clear() {
        prefs?.edit()?.remove(KEY_PENDING)?.apply()
    }
}
