package ru.meerbot.sdk.network

import okio.BufferedSource

/** Сырое SSE-событие: имя (по спецификации по умолчанию `message`) и склеенные строки `data`. */
data class SseEvent(val name: String, val data: String)

/**
 * Построчный разбор `text/event-stream` поверх Okio.
 *
 * Почему свой разбор, а не `okhttp-sse`: нам нужен доступ к телу ответа при не-2xx (там лежит
 * машинный код ошибки платформы) и полный контроль над завершением потока — `data: [DONE]`
 * приходит безымянным событием. Okio при этом уже режет строки по `\n`/`\r\n` и не рвёт UTF-8
 * на границе TCP-чанка, так что побайтовый парсер, как на iOS, здесь не нужен.
 *
 * Класс намеренно не знает про сеть — его можно проверять на строке.
 */
class SseReader(private val source: BufferedSource) {

    /**
     * Читать поток, вызывая [onEvent] на каждое завершённое событие. Возвращает управление,
     * когда поток закрыт. Незавершённый последний блок (сервер закрыл соединение без пустой
     * строки) тоже отдаётся — иначе терялось бы последнее событие.
     */
    suspend fun read(onEvent: suspend (SseEvent) -> Unit) {
        var name: String? = null
        val data = StringBuilder()

        suspend fun dispatch() {
            if (name == null && data.isEmpty()) return
            onEvent(SseEvent(name ?: DEFAULT_EVENT_NAME, data.toString()))
            name = null
            data.setLength(0)
        }

        while (true) {
            val line = source.readUtf8Line() ?: break

            when {
                // Пустая строка — граница события.
                line.isEmpty() -> dispatch()

                // Комментарий/keep-alive (`: ping`) — игнорируем.
                line.startsWith(":") -> Unit

                line.startsWith("event:") -> name = line.removePrefix("event:").trimStart()

                line.startsWith("data:") -> {
                    if (data.isNotEmpty()) data.append('\n')
                    data.append(line.removePrefix("data:").removePrefix(" "))
                }

                // id:/retry: и незнакомые поля контракта не несут — пропускаем.
                else -> Unit
            }
        }

        dispatch()
    }

    companion object {
        const val DEFAULT_EVENT_NAME = "message"
    }
}
