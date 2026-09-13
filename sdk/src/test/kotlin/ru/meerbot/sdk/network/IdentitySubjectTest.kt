package ru.meerbot.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение «что значит этот identify». Хост выпускает свежий токен на каждый вход в чат
 * (токены живут минуты), поэтому лента обязана сбрасываться только при смене человека, а не
 * при смене строки токена — иначе она мигала бы и обрывала стримящийся ответ. Но «тот же
 * человек» — только когда это известно: иначе новый человек мог бы увидеть тред прежнего.
 */
class IdentitySubjectTest {

    private fun token(payload: String) = "$HEADER.$payload.signature"

    @Test
    fun `sub читается из полезной нагрузки`() {
        // {"sub":"user-42","iat":1757750400}
        assertEquals("user-42", IdentitySubject.of(token("eyJzdWIiOiJ1c2VyLTQyIiwiaWF0IjoxNzU3NzUwNDAwfQ")))
    }

    @Test
    fun `sub обрезается по краям, как на сервере`() {
        assertEquals("user-42", IdentitySubject.of(Jwt.withSub("  user-42 ")))
        assertNull(IdentitySubject.of(Jwt.withSub("   ")))
    }

    @Test
    fun `base64url без паддинга с символами алфавита url`() {
        // {"sub":"user-42","x":"?>"} — в кодировке есть `-`, длина не кратна четырём.
        val payload = "eyJzdWIiOiJ1c2VyLTQyIiwieCI6Ij8-In0"
        assertTrue(payload.contains('-') && payload.length % 4 != 0)

        assertEquals("user-42", IdentitySubject.of(token(payload)))
        // С паддингом — то же самое: часть выпускающих библиотек его оставляет.
        assertEquals("user-42", IdentitySubject.of(token("$payload=")))
    }

    @Test
    fun `нет sub — нет субъекта`() {
        // {"iat":1757750400}
        assertNull(IdentitySubject.of(token("eyJpYXQiOjE3NTc3NTA0MDB9")))
        // {"sub":""}
        assertNull(IdentitySubject.of(token("eyJzdWIiOiIifQ")))
        // {"sub":42} — числовой sub на iOS тоже не считается субъектом.
        assertNull(IdentitySubject.of(token("eyJzdWIiOjQyfQ")))
    }

    @Test
    fun `нечитаемый токен — нет субъекта, а не падение`() {
        assertNull(IdentitySubject.of("not-a-jwt"))
        assertNull(IdentitySubject.of("a.b"))
        assertNull(IdentitySubject.of("$HEADER.eyJzdWIiOiJ1c2VyLTQyIn0.sig.extra"))
        assertNull(IdentitySubject.of(token("@@@")))
        assertNull(IdentitySubject.of(token("eyJzd"))) // длина по модулю 4 == 1
        assertNull(IdentitySubject.of(token("bm90IGpzb24"))) // "not json"
        assertNull(IdentitySubject.of(token("")))
    }

    @Test
    fun `нечитаемые токены сравниваются строкой`() {
        assertEquals("opaque-1", IdentitySubject.key("opaque-1"))
        assertEquals("user-42", IdentitySubject.key(Jwt.withSub("user-42")))
    }

    @Test
    fun `свежий токен того же пользователя — обновление`() {
        val first = hashOf(Jwt.withSub("user-42", iat = 1))
        val refreshed = hashOf(Jwt.withSub("user-42", iat = 2))

        assertEquals(IdentityChange.Refresh, IdentitySubject.change(first, refreshed, newSubjectReadable = true))
    }

    @Test
    fun `токен другого пользователя без выхода — смена`() {
        assertEquals(
            IdentityChange.Switch,
            IdentitySubject.change(hashOf(Jwt.withSub("user-42")), hashOf(Jwt.withSub("user-7")), newSubjectReadable = true),
        )
    }

    /**
     * Прежний хеш неизвестен: вход был на 0.2.8 (хеша тогда не было), хеш не прочитался, либо
     * это действительно первый вход. Устройство могло остаться за человеком, которого SDK не
     * знает, — без выхода новый увидел бы его тред, если сервер отклонит токен.
     */
    @Test
    fun `прежний хеш неизвестен — выход и вход`() {
        assertEquals(IdentityChange.Switch, IdentitySubject.change(null, hashOf(Jwt.withSub("user-7")), newSubjectReadable = true))
    }

    /** Нечитаемый `sub` — неизвестно, тот ли это человек, даже если строка токена та же. */
    @Test
    fun `нечитаемый sub нового токена — никогда не обновление`() {
        val opaque = hashOf("opaque-token")

        assertEquals(IdentityChange.Switch, IdentitySubject.change(opaque, opaque, newSubjectReadable = false))
    }

    @Test
    fun `выход — всегда выход`() {
        assertEquals(IdentityChange.Logout, IdentitySubject.change(hashOf(Jwt.withSub("user-42")), null, newSubjectReadable = false))
        assertEquals(IdentityChange.Logout, IdentitySubject.change(null, null, newSubjectReadable = false))
    }

    @Test
    fun `хеш привязан к установке и не содержит sub`() {
        val a = IdentitySubject.hash("and-1", "user-42")
        assertNotEquals(a, IdentitySubject.hash("and-2", "user-42"))
        assertEquals(a, IdentitySubject.hash("and-1", "user-42"))
        assertEquals(64, a.length)
        assertTrue(!a.contains("user-42"))
    }

    private fun hashOf(token: String) = IdentitySubject.hash("and-1", IdentitySubject.key(token))

    private companion object {
        /** {"alg":"HS256","typ":"JWT"} */
        const val HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    }
}

/** JWT-подобная строка с заданным `sub`. Подпись SDK не проверяет, поэтому она условная. */
object Jwt {
    fun withSub(sub: String, iat: Long = 1_757_750_400): String {
        val encoder = java.util.Base64.getUrlEncoder().withoutPadding()
        val header = encoder.encodeToString("""{"alg":"HS256","typ":"JWT"}""".toByteArray())
        val payload = encoder.encodeToString(
            org.json.JSONObject().put("sub", sub).put("iat", iat).toString().toByteArray(),
        )
        return "$header.$payload.signature"
    }
}
