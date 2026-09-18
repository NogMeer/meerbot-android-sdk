package ru.meerbot.sdk.network

import org.json.JSONArray
import org.json.JSONObject
import ru.meerbot.sdk.state.Attachment

/**
 * Ответ загрузки `POST /api/v1/mobile/upload`. Клиент передаёт `uploadId` в отправку сообщения
 * (`uploadIds[]`), остальное — для оптимистичной отрисовки чипа/строки до подтверждения историей.
 */
data class UploadResult(
    val uploadId: String,
    val status: String,
    val kind: String,
    val mime: String,
    val fileName: String,
    val size: Long,
) {
    internal companion object {
        fun from(json: JSONObject): UploadResult {
            val uploadId = json.optStringOrNull("uploadId") ?: throw MeerBotError.InvalidResponse
            return UploadResult(
                uploadId = uploadId,
                status = json.optStringOrNull("status") ?: "ready",
                kind = json.optStringOrNull("kind") ?: Attachment.KIND_DOCUMENT,
                mime = json.optStringOrNull("mime") ?: "application/octet-stream",
                fileName = json.optStringOrNull("fileName") ?: uploadId,
                size = if (json.isNull("size")) 0L else json.optLong("size", 0L),
            )
        }
    }
}

/**
 * Разбор массива `attachments` из истории и SSE-события менеджера. Незнакомые поля игнорируются,
 * запись без `mediaId` пропускается (без неё вложение не нарисовать и не скачать). Общая точка,
 * чтобы история и стрим не разъезжались в форме.
 */
internal fun parseAttachments(array: JSONArray?): List<Attachment> {
    if (array == null || array.length() == 0) return emptyList()
    val result = ArrayList<Attachment>(array.length())
    for (i in 0 until array.length()) {
        val item = array.optJSONObject(i) ?: continue
        val mediaId = item.optStringOrNull("mediaId") ?: continue
        val mime = item.optStringOrNull("mime") ?: "application/octet-stream"
        result += Attachment(
            mediaId = mediaId,
            // Вид сервер присылает, но на всякий случай выводим из mime, если поля нет.
            kind = item.optStringOrNull("kind") ?: Attachment.kindFromMime(mime),
            mime = mime,
            fileName = item.optStringOrNull("fileName") ?: mediaId,
            size = if (item.isNull("size")) 0L else item.optLong("size", 0L),
            width = item.optIntOrNull("width"),
            height = item.optIntOrNull("height"),
            duration = item.optIntOrNull("duration"),
        )
    }
    return result
}

private fun JSONObject.optStringOrNull(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

private fun JSONObject.optIntOrNull(key: String): Int? =
    if (isNull(key) || !has(key)) null else optInt(key).takeIf { it > 0 }
