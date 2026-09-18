package ru.meerbot.sdk.network

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.meerbot.sdk.state.Attachment

/** Разбор массива `attachments` и ответа загрузки — общая форма истории и стрима. */
class AttachmentsTest {

    @Test
    fun `полный элемент разбирается`() {
        val list = parseAttachments(
            JSONArray(
                """[{"mediaId":"m1","kind":"image","mime":"image/png","fileName":"a.png",
                    "size":2048,"width":800,"height":600}]"""
            )
        )
        val att = list.single()
        assertEquals("m1", att.mediaId)
        assertEquals(Attachment.KIND_IMAGE, att.kind)
        assertEquals("a.png", att.fileName)
        assertEquals(2048L, att.size)
        assertEquals(800, att.width)
        assertEquals(600, att.height)
        assertNull(att.duration)
    }

    @Test
    fun `запись без mediaId пропускается`() {
        val list = parseAttachments(JSONArray("""[{"kind":"image","mime":"image/png"}]"""))
        assertTrue(list.isEmpty())
    }

    @Test
    fun `вид выводится из mime, если поля kind нет`() {
        val list = parseAttachments(JSONArray("""[{"mediaId":"m1","mime":"video/mp4"}]"""))
        assertEquals(Attachment.KIND_VIDEO, list.single().kind)
    }

    @Test
    fun `null и пустой массив дают пустой список`() {
        assertTrue(parseAttachments(null).isEmpty())
        assertTrue(parseAttachments(JSONArray("[]")).isEmpty())
    }

    @Test
    fun `UploadResult читает ответ загрузки`() {
        val result = UploadResult.from(
            JSONObject(
                """{"uploadId":"up_1","status":"ready","kind":"document",
                    "mime":"application/pdf","fileName":"doc.pdf","size":10}"""
            )
        )
        assertEquals("up_1", result.uploadId)
        assertEquals("ready", result.status)
        assertEquals(Attachment.KIND_DOCUMENT, result.kind)
        assertEquals("doc.pdf", result.fileName)
        assertEquals(10L, result.size)
    }

    @Test
    fun `UploadResult без uploadId — ошибка`() {
        val error = runCatching { UploadResult.from(JSONObject("""{"status":"ready"}""")) }.exceptionOrNull()
        assertTrue(error is MeerBotError.InvalidResponse)
    }

    @Test
    fun `kindFromMime классифицирует по типу верхнего уровня`() {
        assertEquals(Attachment.KIND_IMAGE, Attachment.kindFromMime("image/jpeg"))
        assertEquals(Attachment.KIND_VIDEO, Attachment.kindFromMime("video/mp4"))
        assertEquals(Attachment.KIND_AUDIO, Attachment.kindFromMime("audio/mpeg"))
        assertEquals(Attachment.KIND_DOCUMENT, Attachment.kindFromMime("application/pdf"))
    }
}
