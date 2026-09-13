package ru.meerbot.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.meerbot.sdk.testing.FakeSharedPreferences

/**
 * `MeerBot.identify` без `Context`: что уходит в клиент и когда чистится лента. Сеть не
 * нужна — решение видно по флагу выхода и клиентскому состоянию.
 */
class IdentityCoordinatorTest {

    private val prefs = FakeSharedPreferences()
    private var feedResets = 0

    private fun client(flag: LogoutFlagStore) = ApiClient(
        MeerBotConfiguration(apiKey = "pk_live_mobile", baseUrl = "http://127.0.0.1:9", sdkVersion = "0.2.9-test"),
        "11111111-1111-1111-1111-111111111111",
        INSTALLATION,
        ApiClient.defaultHttpClient(),
        flag,
    )

    /** Как `configure`: новый клиент поверх тех же prefs — это и есть «после перезапуска». */
    private fun coordinator(
        flag: LogoutFlagStore = PrefsLogoutFlagStore(prefs),
        api: ApiClient = client(flag),
    ) = IdentityCoordinator(
        client = api,
        installationId = INSTALLATION,
        subjects = PrefsSubjectHashStore(prefs),
        resetFeed = { feedResets++ },
    )

    /**
     * A вошёл, потом без выхода вошёл B (приложение сменило аккаунт). Если токен B сервер не
     * примет (устарел), связь A осталась бы, и B увидел бы его тред: одной очистки ленты мало.
     */
    @Test
    fun `вход другого пользователя без выхода ставит флаг выхода и сбрасывает состояние`() {
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        val identity = coordinator(flag, api)
        assertEquals(IdentityChange.SignIn, identity.apply(Jwt.withSub("user-A")))
        assertFalse(flag.pending)
        api.rememberConversationId(77)

        val change = identity.apply(Jwt.withSub("user-B"))

        assertEquals(IdentityChange.Switch, change)
        assertTrue(flag.pending)
        assertNull(api.conversationId)
        assertEquals(IdentityStatus.NotProvided, api.identityStatus)
        assertEquals(2, feedResets)
    }

    @Test
    fun `свежий токен того же пользователя флаг не ставит и ленту не чистит`() {
        val flag = InMemoryLogoutFlagStore()
        val api = client(flag)
        val identity = coordinator(flag, api)
        identity.apply(Jwt.withSub("user-A", iat = 1))
        api.rememberConversationId(77)

        val change = identity.apply(Jwt.withSub("user-A", iat = 2))

        assertEquals(IdentityChange.Refresh, change)
        assertFalse(flag.pending)
        assertEquals(77L, api.conversationId)
        assertEquals(1, feedResets)
    }

    @Test
    fun `после перезапуска тот же пользователь ленту не чистит, другой — смена`() {
        coordinator().apply(Jwt.withSub("user-A", iat = 1))
        feedResets = 0

        val flag = InMemoryLogoutFlagStore()
        val restarted = coordinator(flag)

        assertEquals(IdentityChange.Refresh, restarted.apply(Jwt.withSub("user-A", iat = 2)))
        assertEquals(0, feedResets)
        assertFalse(flag.pending)
        assertEquals(IdentityChange.Switch, restarted.apply(Jwt.withSub("user-B")))
        assertTrue(flag.pending)
    }

    @Test
    fun `на диск ложится хеш, а не sub`() {
        coordinator().apply(Jwt.withSub("user-42"))

        val stored = prefs.values[PrefsSubjectHashStore.KEY] as String
        assertEquals(IdentitySubject.hash(INSTALLATION, "user-42"), stored)
        assertTrue(prefs.values.values.none { it.toString().contains("user-42") })
    }

    @Test
    fun `выход стирает хеш, и следующий вход — вход, а не смена`() {
        val flag = InMemoryLogoutFlagStore()
        val identity = coordinator(flag)
        identity.apply(Jwt.withSub("user-A"))

        assertEquals(IdentityChange.Logout, identity.apply(null))
        assertTrue(flag.pending)
        assertNull(prefs.values[PrefsSubjectHashStore.KEY])

        assertEquals(IdentityChange.SignIn, identity.apply(Jwt.withSub("user-A")))
        assertEquals(3, feedResets)
    }

    private companion object {
        const val INSTALLATION = "and-22222222-2222-2222-2222-222222222222"
    }
}
