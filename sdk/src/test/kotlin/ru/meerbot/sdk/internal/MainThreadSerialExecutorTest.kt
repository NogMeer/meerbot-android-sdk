package ru.meerbot.sdk.internal

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Порядок применения `identify`/`configure`/`reset`. Главный поток здесь — однопоточный
 * исполнитель с именем: так проверяется и очередь, и то, на каком потоке выполнился блок.
 */
class MainThreadSerialExecutorTest {

    private val main = Executors.newSingleThreadExecutor { Thread(it, MAIN) }
    private val executor = MainThreadSerialExecutor(
        isMainThread = { Thread.currentThread().name == MAIN },
        postToMain = { main.execute(it) },
    )
    private val log = Collections.synchronizedList(mutableListOf<String>())

    @After
    fun tearDown() {
        main.shutdownNow()
    }

    private fun record(name: String) {
        log += "$name@${Thread.currentThread().name}"
    }

    private fun drainMain() {
        main.submit {}.get(5, TimeUnit.SECONDS)
    }

    @Test
    fun `выход и вход с фонового потока применяются на главном по порядку`() {
        val background = Thread {
            executor.execute { record("logout") }
            executor.execute { record("identify-B") }
        }
        background.start()
        background.join(5_000)
        drainMain()

        assertEquals(listOf("logout@$MAIN", "identify-B@$MAIN"), log)
    }

    /**
     * Фоновый поток поставил выход в очередь, пока главный был занят; затем главный сам зовёт
     * `identify(B)`. Выполни он его сразу — B применился бы раньше выхода, и выход отвязал бы B.
     */
    @Test
    fun `вызов с главного потока встаёт за уже поставленными`() {
        val logoutQueued = CountDownLatch(1)
        main.execute {
            logoutQueued.await(5, TimeUnit.SECONDS)
            executor.execute { record("identify-B") }
        }
        Thread {
            executor.execute { record("logout") }
            logoutQueued.countDown()
        }.start()

        assertTrue(logoutQueued.await(5, TimeUnit.SECONDS))
        drainMain()
        drainMain()

        assertEquals(listOf("logout@$MAIN", "identify-B@$MAIN"), log)
    }

    /**
     * Сброс ленты уведомил подписчика хоста, и тот прямо из колбэка зовёт `identify`. Выполнись
     * вложенный вызов сразу, он применился бы посреди внешнего — над наполовину изменённым
     * состоянием.
     */
    @Test
    fun `вложенный вызов из блока выполняется после него`() {
        main.submit {
            executor.execute {
                record("outer-start")
                executor.execute { record("nested") }
                record("outer-end")
            }
        }.get(5, TimeUnit.SECONDS)
        drainMain()

        assertEquals(listOf("outer-start@$MAIN", "outer-end@$MAIN", "nested@$MAIN"), log)
    }

    /** Вызов с главного потока после вложенного встаёт за ним, а не проскакивает вперёд. */
    @Test
    fun `вызов после вложенного встаёт за ним`() {
        main.submit {
            executor.execute { executor.execute { record("nested") } }
            executor.execute { record("next") }
        }.get(5, TimeUnit.SECONDS)
        drainMain()
        drainMain()

        assertEquals(listOf("nested@$MAIN", "next@$MAIN"), log)
    }

    @Test
    fun `с главного потока при пустой очереди — сразу`() {
        val ranInline = main.submit<Boolean> {
            var ran = false
            executor.execute { ran = true }
            ran
        }.get(5, TimeUnit.SECONDS)

        assertTrue(ranInline)
    }

    private companion object {
        const val MAIN = "fake-main"
    }
}
