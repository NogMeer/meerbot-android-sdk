package ru.meerbot.sdk.network

import org.json.JSONObject
import java.io.ByteArrayOutputStream

/**
 * Чей это identity-токен — только чтобы решить, чистить ли ленту. Паритет с iOS
 * `MeerBot.identitySubject(of:)`.
 *
 * Подпись здесь не проверяется и не должна: связь устройства с пользователем устанавливает
 * сервер, а по этому значению решается лишь, сменился ли человек на экране.
 */
internal object IdentitySubject {

    /** `sub` из полезной нагрузки JWT. `null` — токен не JWT или строкового `sub` в нём нет. */
    fun of(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val payload = decodeBase64Url(parts[1]) ?: return null
        val claims = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            ?: return null
        // `opt` + приведение, а не `optString`: тот превратил бы числовой `sub` в строку, а
        // iOS такой токен считает токеном без `sub` — решения SDK не должны расходиться.
        return (claims.opt("sub") as? String)?.takeIf { it.isNotEmpty() }
    }

    /**
     * Ключ сравнения: `sub`, а у нечитаемого токена — сама строка (сравнивать больше нечего).
     * `null` — токена нет (выход).
     */
    fun key(token: String?): String? = token?.let { of(it) ?: it }

    /**
     * Чистить ли ленту на `identify(newToken)`.
     *
     * Выход — всегда: связь могла остаться с прошлого запуска, когда `identify` в этом
     * процессе ещё не звали. Токен — только если сменился человек: хост выпускает свежий
     * токен на каждый вход в чат, и сравнение строк сбрасывало бы ленту (и обрывало
     * стримящийся ответ) на каждом таком вызове.
     */
    fun shouldResetFeed(previousKey: String?, newToken: String?): Boolean =
        newToken == null || key(newToken) != previousKey

    /**
     * base64url без обязательного паддинга. Свой разбор, а не `java.util.Base64`: тот есть
     * только с API 26 (minSdk SDK — 24), а `android.util.Base64` в JVM-тестах — заглушка.
     */
    private fun decodeBase64Url(input: String): ByteArray? {
        val body = input.trimEnd('=')
        if (body.length % 4 == 1) return null
        val out = ByteArrayOutputStream(body.length * 3 / 4)
        var buffer = 0
        var bits = 0
        for (c in body) {
            val value = when (c) {
                in 'A'..'Z' -> c - 'A'
                in 'a'..'z' -> c - 'a' + 26
                in '0'..'9' -> c - '0' + 52
                '-', '+' -> 62
                '_', '/' -> 63
                else -> return null
            }
            buffer = (buffer shl 6) or value
            bits += 6
            if (bits >= 8) {
                bits -= 8
                out.write((buffer shr bits) and 0xFF)
                buffer = buffer and ((1 shl bits) - 1)
            }
        }
        return out.toByteArray()
    }
}
