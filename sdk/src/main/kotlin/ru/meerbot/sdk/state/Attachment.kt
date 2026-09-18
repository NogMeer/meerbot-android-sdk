package ru.meerbot.sdk.state

/**
 * Вложение сообщения. Контракт полей совпадает с бэкендом (`attachments[]` в истории и
 * SSE-событии `manager_message`) и с iOS SDK.
 *
 * `mediaId` — идентификатор медиа на сервере; картинка/файл раздаётся по
 * `GET /api/v1/mobile/media/<messageId>/<mediaId>` с тем же Bearer, что и остальной канал.
 * Для только что отправленного пользователем вложения, ещё не подтверждённого историей,
 * `mediaId` имеет префикс [LOCAL_PREFIX]: тогда байты берутся из локального кэша
 * ([ru.meerbot.sdk.state.LocalMediaCache]), а не из сети — свой файл незачем скачивать обратно.
 */
data class Attachment(
    val mediaId: String,
    /** `image` | `video` | `audio` | `document`. */
    val kind: String,
    val mime: String,
    val fileName: String,
    val size: Long,
    val width: Int? = null,
    val height: Int? = null,
    /** Длительность видео/аудио в секундах, если сервер её знает. */
    val duration: Int? = null,
) {
    val isLocal: Boolean get() = mediaId.startsWith(LOCAL_PREFIX)
    val isImage: Boolean get() = kind == KIND_IMAGE

    companion object {
        const val KIND_IMAGE = "image"
        const val KIND_VIDEO = "video"
        const val KIND_AUDIO = "audio"
        const val KIND_DOCUMENT = "document"

        /** Префикс `mediaId` у ещё не подтверждённого сервером вложения (рисуется из кэша). */
        const val LOCAL_PREFIX = "local:"

        /** Классификация по MIME — та же логика, что на сервере: тип верхнего уровня. */
        fun kindFromMime(mime: String): String = when {
            mime.startsWith("image/") -> KIND_IMAGE
            mime.startsWith("video/") -> KIND_VIDEO
            mime.startsWith("audio/") -> KIND_AUDIO
            else -> KIND_DOCUMENT
        }
    }
}

/**
 * Выбранный, но ещё НЕ загруженный файл: байты и метаданные из `ContentResolver`. Загрузку
 * (`POST /mobile/upload`) делает контроллер уже после появления оптимистичной строки в ленте,
 * поэтому здесь только сырьё. Не data-класс: сравнивать байтовые массивы по ссылке незачем.
 */
class OutgoingAttachment(
    val bytes: ByteArray,
    val fileName: String,
    val mime: String,
) {
    val kind: String get() = Attachment.kindFromMime(mime)
    val size: Long get() = bytes.size.toLong()
}
