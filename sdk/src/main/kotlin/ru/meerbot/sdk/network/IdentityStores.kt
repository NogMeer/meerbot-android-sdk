package ru.meerbot.sdk.network

import android.content.SharedPreferences
import android.util.Log
import java.io.IOException
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

    /** Поставить флаг и дождаться записи на диск (если хранилище дисковое). */
    fun persistPending() {
        pending = true
    }
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

/**
 * Монотонный счётчик смен identity на этой установке (`identitySeq` рукопожатия).
 *
 * Сервер хранит максимум, пришедший с установки, и запрос с МЕНЬШИМ значением для identity
 * игнорирует целиком — включая флаг выхода. Так порядок «выход → вход» задаётся счётчиком, а не
 * часами бэкенда интегратора: задержанная регистрация со свежим токеном прежнего пользователя
 * больше не отменяет выход, случившийся после неё.
 *
 * Счётчик растёт в той же критической секции, что и флаг выхода (любой выход и любая смена
 * человека), и переживает перезапуск — иначе следующий запуск ушёл бы с меньшим значением, и
 * сервер перестал бы принимать его identity вовсе.
 */
internal interface IdentitySeqStore {
    val value: Long

    /**
     * Поднять счётчик на единицу и вернуть новое значение.
     *
     * @param durable дождаться записи на диск (выход: процесс могут убить сразу после вызова).
     */
    fun increment(durable: Boolean = false): Long

    /**
     * Поднять счётчик до [value], если он ниже: сервер сообщил в ответе `identity.seq` больше
     * отправленного (локальный счётчик потерян со сбросом хранилища). Без этого все дальнейшие
     * запросы установки были бы для сервера устаревшими, и identity не применилась бы никогда.
     */
    fun raiseTo(value: Long)
}

internal class InMemoryIdentitySeqStore(value: Long = 0L) : IdentitySeqStore {
    private val lock = Any()

    @Volatile
    override var value: Long = value
        private set

    override fun increment(durable: Boolean): Long = synchronized(lock) {
        value = nextSeq(value)
        value
    }

    override fun raiseTo(value: Long) = synchronized(lock) {
        if (value > this.value) this.value = clampSeq(value)
    }
}

internal class PrefsIdentitySeqStore(
    prefs: SharedPreferences,
    onError: StoreErrorReporter = StoreErrorReporter.Logcat,
) : IdentitySeqStore {
    private val lock = Any()
    private val stored = PrefsValue(
        prefs = prefs,
        key = KEY,
        errorPrefix = "identity_seq",
        default = 0L,
        read = { key, default -> getLong(key, default) },
        write = { key, value -> putLong(key, value) },
        onError = onError,
    )

    override val value: Long get() = clampSeq(stored.get())

    override fun increment(durable: Boolean): Long = synchronized(lock) {
        val next = nextSeq(stored.get())
        stored.set(next, durable = durable)
        next
    }

    override fun raiseTo(value: Long) {
        synchronized(lock) {
            if (value > stored.get()) stored.set(clampSeq(value))
        }
    }

    companion object {
        const val KEY = "identity_seq"
    }
}

/** Сервер принимает `identitySeq` в диапазоне `0..2147483647`: выше — 400. */
private fun clampSeq(value: Long): Long = value.coerceIn(0L, Int.MAX_VALUE.toLong())

private fun nextSeq(value: Long): Long = clampSeq(clampSeq(value) + 1)

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

    override fun persistPending() = value.set(true, durable = true)

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
 * Запись по умолчанию — `apply()`: in-memory карта prefs обновляется сразу, диск пишется в
 * фоне и досылается системой на `onPause`/остановке сервиса. `commit()` держал бы главный
 * поток на диске (StrictMode DiskWrite), и не просто так, а под замком сессии клиента.
 * `durable = true` — `commit()` на потоке вызывающего, вне замка сессии: для редкой записи,
 * которая обязана пережить убийство процесса сразу после вызова (выход пользователя).
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

    fun set(value: T, durable: Boolean = false) {
        synchronized(lock) {
            memory = value
            written = true
        }
        try {
            val editor = prefs.edit().write(key, value)
            if (!durable) {
                editor.apply()
            } else if (!editor.commit()) {
                onError.report("${errorPrefix}_write_failed", IOException("commit() returned false"))
            }
        } catch (e: SecurityException) {
            onError.report("${errorPrefix}_write_failed", e)
        } catch (e: GeneralSecurityException) {
            onError.report("${errorPrefix}_write_failed", e)
        }
    }
}
