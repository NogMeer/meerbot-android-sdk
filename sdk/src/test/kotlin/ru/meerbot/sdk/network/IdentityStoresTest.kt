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
