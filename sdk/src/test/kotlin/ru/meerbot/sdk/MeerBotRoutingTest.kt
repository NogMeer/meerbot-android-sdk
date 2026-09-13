package ru.meerbot.sdk

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import ru.meerbot.sdk.internal.MainThreadSerialExecutor

/**
 * Публичные методы синглтона идут через очередь главного потока, а не меняют состояние на
 * потоке вызывающего. Очередь здесь — список: вызов «с фонового потока» только ставит блок, и
 * тест применяет блоки сам, по одному. Уберите маршрутизацию в `MeerBot` — блоков в списке не
 * станет, и тест упадёт. `configure` сюда не входит: ему нужен настоящий `Context`.
 */
class MeerBotRoutingTest {

    private val posted = ArrayList<Runnable>()

    @Before
    fun setUp() {
        MeerBot.executorOverride = MainThreadSerialExecutor(
            isMainThread = { false },
            postToMain = { posted += it },
        )
    }

    @After
    fun tearDown() {
        posted.clear()
        MeerBot.reset()
        posted.forEach { it.run() }
        posted.clear()
        MeerBot.executorOverride = null
    }

    @Test
    fun `identify с фонового потока применяется только в очереди`() {
        MeerBot.identify("token-A")

        assertNull(MeerBot.pendingIdentityTokenForTests)
        assertEquals(1, posted.size)

        posted.removeAt(0).run()

        assertEquals("token-A", MeerBot.pendingIdentityTokenForTests)
    }

    @Test
    fun `вход, выход, вход и сброс применяются в порядке вызова`() {
        MeerBot.identify("token-A")
        MeerBot.identify(null)
        MeerBot.identify("token-B")
        MeerBot.reset()

        assertEquals(4, posted.size)
        assertNull(MeerBot.pendingIdentityTokenForTests)

        posted[0].run()
        assertEquals("token-A", MeerBot.pendingIdentityTokenForTests)
        posted[1].run()
        assertNull(MeerBot.pendingIdentityTokenForTests)
        posted[2].run()
        assertEquals("token-B", MeerBot.pendingIdentityTokenForTests)
        posted[3].run()
        assertNull(MeerBot.pendingIdentityTokenForTests)
    }

    @Test
    fun `preconnect идёт через очередь`() {
        MeerBot.preconnect()

        assertEquals(1, posted.size)
        assertTrue(MeerBot.chatController() == null)
    }
}
