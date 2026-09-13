package ru.meerbot.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.meerbot.sdk.testing.FakeSharedPreferences

/**
 * Хранилища identity поверх prefs. `EncryptedSharedPreferences` с повреждённым keyset бросает
 * `SecurityException`; SDK обязан пережить это без падения хоста и с названной ошибкой в логе.
 */
class IdentityStoresTest {

    private val prefs = FakeSharedPreferences()
    private val errors = mutableListOf<String>()
    private val reporter = StoreErrorReporter { code, _ -> errors += code }

    @Test
    fun `флаг выхода переживает пересоздание хранилища`() {
        PrefsLogoutFlagStore(prefs, reporter).pending = true

        assertTrue(PrefsLogoutFlagStore(prefs, reporter).pending)
        assertTrue(errors.isEmpty())
    }

    @Test
    fun `нечитаемый флаг — умолчание и названная ошибка, а не падение`() {
        prefs.values[PrefsLogoutFlagStore.KEY] = true
        prefs.failReads = true

        assertFalse(PrefsLogoutFlagStore(prefs, reporter).pending)
        assertEquals(listOf("logout_flag_read_failed"), errors)
    }

    @Test
    fun `незаписанный флаг остаётся в памяти процесса`() {
        prefs.failWrites = true
        prefs.failReads = true
        val store = PrefsLogoutFlagStore(prefs, reporter)

        store.pending = true

        assertTrue(store.pending)
        assertEquals(listOf("logout_flag_write_failed"), errors)
    }

    @Test
    fun `клиент с битым хранилищем выходит без исключения`() {
        prefs.failWrites = true
        prefs.failReads = true
        val flag = PrefsLogoutFlagStore(prefs, reporter)
        val api = ApiClient(
            MeerBotConfiguration(apiKey = "pk_live_mobile", baseUrl = "http://127.0.0.1:9", sdkVersion = "0.2.9-test"),
            "11111111-1111-1111-1111-111111111111",
            "and-1",
            ApiClient.defaultHttpClient(),
            flag,
        )

        api.logout()

        assertTrue(flag.pending)
    }

    /**
     * `identify(null)` с фонового потока: процесс могут убить до того, как главный поток
     * применит выход, и отложенная запись `apply()` не успеет лечь на диск.
     */
    @Test
    fun `выход пишется на диск синхронно`() {
        PrefsLogoutFlagStore(prefs, reporter).persistPending()

        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
        assertEquals(true, prefs.values[PrefsLogoutFlagStore.KEY])
    }

    @Test
    fun `обычная запись флага — отложенная`() {
        PrefsLogoutFlagStore(prefs, reporter).pending = true

        assertEquals(0, prefs.commits)
        assertEquals(1, prefs.applies)
    }

    @Test
    fun `незаписанный синхронный выход остаётся в памяти и назван в логе`() {
        prefs.failWrites = true
        val store = PrefsLogoutFlagStore(prefs, reporter)

        store.persistPending()

        assertTrue(store.pending)
        assertEquals(listOf("logout_flag_write_failed"), errors)
    }

    // ─── Счётчик identity ─────────────────────────────────────────────────────────────────

    @Test
    fun `счётчик identity растёт и переживает пересоздание хранилища`() {
        assertEquals(1L, PrefsIdentitySeqStore(prefs, reporter).increment())

        assertEquals(1L, PrefsIdentitySeqStore(prefs, reporter).value)
        assertEquals(2L, PrefsIdentitySeqStore(prefs, reporter).increment())
        assertTrue(errors.isEmpty())
    }

    /** Выход пишется синхронно: процесс могут убить до того, как отложенная запись ляжет на диск. */
    @Test
    fun `счётчик выхода пишется на диск синхронно`() {
        PrefsIdentitySeqStore(prefs, reporter).increment(durable = true)

        assertEquals(1, prefs.commits)
        assertEquals(0, prefs.applies)
        assertEquals(1L, prefs.values[PrefsIdentitySeqStore.KEY])
    }

    @Test
    fun `обычный рост счётчика — отложенная запись`() {
        PrefsIdentitySeqStore(prefs, reporter).increment()

        assertEquals(0, prefs.commits)
        assertEquals(1, prefs.applies)
    }

    @Test
    fun `нечитаемый счётчик — ноль и названная ошибка, а не падение`() {
        prefs.values[PrefsIdentitySeqStore.KEY] = 7L
        prefs.failReads = true

        assertEquals(0L, PrefsIdentitySeqStore(prefs, reporter).value)
        assertEquals(listOf("identity_seq_read_failed"), errors)
    }

    @Test
    fun `незаписанный счётчик остаётся в памяти процесса`() {
        prefs.failWrites = true
        prefs.failReads = true
        val store = PrefsIdentitySeqStore(prefs, reporter)

        store.increment()

        assertEquals(1L, store.value)
        // Чтение сбойного хранилища тоже названо: рост идёт от прочитанного значения.
        assertEquals(listOf("identity_seq_read_failed", "identity_seq_write_failed"), errors)
    }

    /** Серверное значение больше локального: счётчик догоняет его, ниже — не опускается. */
    @Test
    fun `счётчик поднимается до серверного и не падает обратно`() {
        val store = PrefsIdentitySeqStore(prefs, reporter)
        store.increment()

        store.raiseTo(40L)
        assertEquals(40L, store.value)

        store.raiseTo(3L)
        assertEquals(40L, store.value)
        assertEquals(41L, store.increment())
    }

    /** Сервер принимает `identitySeq` в `0..2147483647`: выше — 400 на каждом рукопожатии. */
    @Test
    fun `счётчик не выходит за предел, принимаемый сервером`() {
        val store = PrefsIdentitySeqStore(prefs, reporter)

        store.raiseTo(Long.MAX_VALUE)
        assertEquals(Int.MAX_VALUE.toLong(), store.value)
        assertEquals(Int.MAX_VALUE.toLong(), store.increment())
    }

    @Test
    fun `битый счётчик на диске не выходит за предел`() {
        prefs.values[PrefsIdentitySeqStore.KEY] = Long.MAX_VALUE

        assertEquals(Int.MAX_VALUE.toLong(), PrefsIdentitySeqStore(prefs, reporter).value)
    }

    @Test
    fun `хеш субъекта пишется, читается и стирается`() {
        PrefsSubjectHashStore(prefs, reporter).hash = "abc"
        assertEquals("abc", PrefsSubjectHashStore(prefs, reporter).hash)

        PrefsSubjectHashStore(prefs, reporter).hash = null
        assertNull(PrefsSubjectHashStore(prefs, reporter).hash)
        assertFalse(prefs.values.containsKey(PrefsSubjectHashStore.KEY))
    }

    @Test
    fun `нечитаемый хеш — нет хеша и названная ошибка`() {
        prefs.values[PrefsSubjectHashStore.KEY] = "abc"
        prefs.failReads = true

        assertNull(PrefsSubjectHashStore(prefs, reporter).hash)
        assertEquals(listOf("identity_subject_read_failed"), errors)
    }
}
