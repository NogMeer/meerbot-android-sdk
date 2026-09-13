package ru.meerbot.sdk.network

import android.content.SharedPreferences
import android.util.Log
import java.security.GeneralSecurityException

/**
 * Где лежит сигнал «пользователь вышел», пока сервер его не подтвердил.
 *
 * Регистрация ленивая (первое открытие чата), а между `identify(null)` и ней процесс могут
 * убить. Потерянный сигнал оставил бы устройство привязанным к прежнему пользователю, и
 * следующий человек на телефоне увидел бы чужую переписку, — поэтому SDK хранит флаг в
 * prefs, а в тестах хватает памяти.
 */
internal interface LogoutFlagStore {
    var pending: Boolean
}

/** Флаг в памяти процесса: для тестов и для [ApiClient], собранного хостом напрямую. */
internal class InMemoryLogoutFlagStore(pending: Boolean = false) : LogoutFlagStore {
    @Volatile
    override var pending: Boolean = pending
}

/**
 * Хеш последнего применённого `sub` на этой установке. По нему `identify` отличает свежий
 * токен того же человека (ленту не трогать) от входа другого (выход + вход) — в том числе
 * первым вызовом после перезапуска процесса.
 */
internal interface SubjectHashStore {
    var hash: String?
}

internal class InMemorySubjectHashStore(hash: String? = null) : SubjectHashStore {
    @Volatile
    override var hash: String? = hash
}

/** Куда сообщать о сбое хранилища. Код — машинный, для поиска в логах хоста. */
internal fun interface StoreErrorReporter {
    fun report(code: String, error: Throwable)

    companion object {
        val Logcat = StoreErrorReporter { code, error -> Log.e("MeerBot", code, error) }
    }
}

internal class PrefsLogoutFlagStore(
    prefs: SharedPreferences,
    onError: StoreErrorReporter = StoreErrorReporter.Logcat,
) : LogoutFlagStore {
    private val value = PrefsValue(
        prefs = prefs,
        key = KEY,
        errorPrefix = "logout_flag",
        default = false,
        read = { key, default -> getBoolean(key, default) },
        write = { key, value -> putBoolean(key, value) },
        onError = onError,
    )

    override var pending: Boolean
        get() = value.get()
        set(pending) = value.set(pending)

    companion object {
        const val KEY = "pending_logout"
    }
}

internal class PrefsSubjectHashStore(
    prefs: SharedPreferences,
    onError: StoreErrorReporter = StoreErrorReporter.Logcat,
) : SubjectHashStore {
    private val value = PrefsValue<String?>(
        prefs = prefs,
        key = KEY,
        errorPrefix = "identity_subject",
        default = null,
        read = { key, default -> getString(key, default) },
        write = { key, value -> if (value == null) remove(key) else putString(key, value) },
        onError = onError,
    )

    override var hash: String?
        get() = value.get()
        set(hash) = value.set(hash)

    companion object {
        const val KEY = "identity_subject_hash"
    }
}

/**
 * Значение в prefs, которое не роняет хост.
 *
 * `EncryptedSharedPreferences` бросает `SecurityException` на чтении и записи, если keyset
 * повреждён (восстановление из бэкапа на другое устройство, сброс Keystore прошивкой). Упасть
 * здесь — значит уронить `identify(null)` у хоста или рукопожатие чата. Поэтому сбой пишется
 * в лог машинным кодом, а значение берётся из памяти: записанное в этом процессе — главнее
 * диска (при исправном хранилище они совпадают), не записанное — умолчание.
 *
 * Запись — `apply()`: in-memory карта prefs обновляется сразу, диск пишется в фоне и
 * досылается системой на `onPause`/остановке сервиса. `commit()` держал бы главный поток на
 * диске (StrictMode DiskWrite), и не просто так, а под замком сессии клиента.
 */
private class PrefsValue<T>(
    private val prefs: SharedPreferences,
    private val key: String,
    private val errorPrefix: String,
    private val default: T,
    private val read: SharedPreferences.(String, T) -> T,
    private val write: SharedPreferences.Editor.(String, T) -> SharedPreferences.Editor,
    private val onError: StoreErrorReporter,
) {
    private val lock = Any()
    private var written = false
    private var memory: T = default

    fun get(): T {
        synchronized(lock) {
            if (written) return memory
        }
        return try {
            prefs.read(key, default)
        } catch (e: SecurityException) {
            onError.report("${errorPrefix}_read_failed", e)
            default
        } catch (e: GeneralSecurityException) {
            onError.report("${errorPrefix}_read_failed", e)
            default
        }
    }

    fun set(value: T) {
        synchronized(lock) {
            memory = value
            written = true
        }
        try {
            prefs.edit().write(key, value).apply()
        } catch (e: SecurityException) {
            onError.report("${errorPrefix}_write_failed", e)
        } catch (e: GeneralSecurityException) {
            onError.report("${errorPrefix}_write_failed", e)
        }
    }
}
