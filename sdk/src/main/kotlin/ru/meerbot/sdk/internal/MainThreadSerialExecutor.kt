package ru.meerbot.sdk.internal

import android.os.Handler
import android.os.Looper
import java.util.concurrent.atomic.AtomicInteger

/**
 * Применяет изменения состояния SDK на главном потоке строго в порядке вызова.
 *
 * Контроллер чата и его задачи (`startJob`, `streamJob`, `pollJob`) — обычные поля, которые
 * читает и пишет главный поток. Хост же зовёт `identify` откуда угодно: из колбэка OkHttp,
 * с потока нативных модулей React Native. Сброс ленты с чужого потока гонялся бы с экраном:
 * двойной старт или потерянная запись `streamJob`, и поток прежнего пользователя продолжал
 * бы писать в очищенную ленту.
 *
 * С главного потока блок выполняется сразу, но только если в очереди ничего нет. Иначе он
 * встаёт за уже поставленными: `identify(null)` с фонового потока и следом `identify(B)` с
 * главного должны примениться в порядке вызова, а не «главный первым».
 */
internal class MainThreadSerialExecutor(
    private val isMainThread: () -> Boolean,
    private val postToMain: (Runnable) -> Unit,
) {
    private val queued = AtomicInteger()

    fun execute(block: () -> Unit) {
        if (isMainThread() && queued.get() == 0) {
            block()
            return
        }
        queued.incrementAndGet()
        postToMain(
            Runnable {
                try {
                    block()
                } finally {
                    queued.decrementAndGet()
                }
            },
        )
    }

    companion object {
        fun forMainLooper(): MainThreadSerialExecutor {
            val handler = Handler(Looper.getMainLooper())
            return MainThreadSerialExecutor(
                isMainThread = { Looper.myLooper() == Looper.getMainLooper() },
                postToMain = { handler.post(it) },
            )
        }
    }
}
