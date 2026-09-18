package ru.meerbot.sdk.state

/**
 * Кэш байтов только что выбранных вложений на время жизни процесса: свой файл незачем
 * скачивать обратно с сервера, а до подтверждения историей серверного `mediaId` у него ещё нет
 * ([Attachment.LOCAL_PREFIX]). Ключ — локальный `mediaId`.
 *
 * Живёт до перезапуска процесса: после него та же строка приедет историей с серверным
 * `mediaId` и нарисуется уже из сети. Ограничен по суммарному объёму — переписка коротка,
 * но подстраховка от накопления крупных видео обязательна; вытесняется самое старое (порядок
 * вставки держит [LinkedHashMap]). Все обращения под монитором объекта: карта не потокобезопасна,
 * а кладут её из корутины отправки, читают — из рендера.
 */
internal object LocalMediaCache {

    /** Потолок суммарного объёма кэша (16 МБ): диагностика по объёму, не по числу файлов. */
    private const val MAX_TOTAL_BYTES = 16L * 1024 * 1024

    private val entries = LinkedHashMap<String, ByteArray>()
    private var totalBytes = 0L

    @Synchronized
    fun put(mediaId: String, bytes: ByteArray) {
        entries.remove(mediaId)?.let { totalBytes -= it.size }
        // Файл крупнее всего потолка не кэшируем вовсе: он бы вытеснил всё и не поместился сам.
        if (bytes.size > MAX_TOTAL_BYTES) return
        val it = entries.entries.iterator()
        while (totalBytes + bytes.size > MAX_TOTAL_BYTES && it.hasNext()) {
            totalBytes -= it.next().value.size
            it.remove()
        }
        entries[mediaId] = bytes
        totalBytes += bytes.size
    }

    @Synchronized
    fun get(mediaId: String): ByteArray? = entries[mediaId]

    @Synchronized
    fun remove(mediaId: String) {
        entries.remove(mediaId)?.let { totalBytes -= it.size }
    }

    @Synchronized
    fun clear() {
        entries.clear()
        totalBytes = 0L
    }
}
