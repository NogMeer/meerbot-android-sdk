package ru.meerbot.sdk.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Решение «чистить ли ленту» на `identify`. Хост выпускает свежий токен на каждый вход в чат
 * (токены живут минуты), поэтому лента обязана сбрасываться только при смене человека, а не
 * при смене строки токена — иначе она мигала бы и обрывала стримящийся ответ.
 */
class IdentitySubjectTest {

    private fun token(payload: String) = "$HEADER.$payload.signature"

    @Test
    fun `sub читается из полезной нагрузки`() {
        // {"sub":"user-42","iat":1757750400}
        assertEquals("user-42", IdentitySubject.of(token("eyJzdWIiOiJ1c2VyLTQyIiwiaWF0IjoxNzU3NzUwNDAwfQ")))
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
    fun `свежий токен того же пользователя ленту не чистит`() {
        val first = token("eyJzdWIiOiJ1c2VyLTQyIiwiaWF0IjoxNzU3NzUwNDAwfQ")
        val refreshed = token("eyJzdWIiOiJ1c2VyLTQyIiwieCI6Ij8-In0")
        assertTrue(first != refreshed)

        assertFalse(IdentitySubject.shouldResetFeed(IdentitySubject.key(first), refreshed))
    }

    @Test
    fun `токен другого пользователя ленту чистит`() {
        // {"sub":"user-7"}
        val other = token("eyJzdWIiOiJ1c2VyLTcifQ")
        assertTrue(IdentitySubject.shouldResetFeed("user-42", other))
    }

    @Test
    fun `первый токен в процессе ленту чистит`() {
        // До него клиент был анонимным: лента могла принадлежать прежнему пользователю.
        assertTrue(IdentitySubject.shouldResetFeed(null, token("eyJzdWIiOiJ1c2VyLTcifQ")))
    }

    @Test
    fun `выход чистит ленту всегда`() {
        assertTrue(IdentitySubject.shouldResetFeed("user-42", null))
        assertTrue(IdentitySubject.shouldResetFeed(null, null))
        assertNull(IdentitySubject.key(null))
    }

    @Test
    fun `нечитаемые токены сравниваются строкой`() {
        assertEquals("opaque-1", IdentitySubject.key("opaque-1"))
        assertFalse(IdentitySubject.shouldResetFeed("opaque-1", "opaque-1"))
        assertTrue(IdentitySubject.shouldResetFeed("opaque-1", "opaque-2"))
    }

    private companion object {
        /** {"alg":"HS256","typ":"JWT"} */
        const val HEADER = "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9"
    }
}
