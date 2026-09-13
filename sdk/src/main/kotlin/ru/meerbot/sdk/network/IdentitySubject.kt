package ru.meerbot.sdk.network

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Что означает очередной `identify` по сравнению с последним применённым. */
internal enum class IdentityChange {
    /** Токена не было (аноним или после выхода) — появился. Лента очищается. */
    SignIn,

    /** Свежий токен того же `sub`. Лента не трогается, флаг выхода не ставится. */
    Refresh,

    /** Токен ДРУГОГО `sub` без выхода прежнего: выход + вход одним рукопожатием. */
    Switch,

    /** `identify(null)`. */
    Logout,
}

/**
 * Чей это identity-токен — чтобы решить, чистить ли ленту и рвать ли связь прежнего
 * пользователя. Паритет с iOS `MeerBot.identitySubject(of:)`.
 *
 * Подпись здесь не проверяется и не должна: связь устройства с пользователем устанавливает
 * сервер, а по этому значению решается лишь, сменился ли человек на экране.
 */
internal object IdentitySubject {

    /**
     * `sub` из полезной нагрузки JWT, обрезанный по краям, как его обрезает сервер (иначе
     * `" user-42"` и `"user-42"` были бы для SDK разными людьми, а для сервера — одним).
     * `null` — токен не JWT или строкового непустого `sub` в нём нет.
     */
    fun of(token: String): String? {
        val parts = token.split(".")
        if (parts.size != 3) return null
        val payload = decodeBase64Url(parts[1]) ?: return null
        val claims = runCatching { JSONObject(String(payload, Charsets.UTF_8)) }.getOrNull()
            ?: return null
        // `opt` + приведение, а не `optString`: тот превратил бы числовой `sub` в строку, а
        // iOS такой токен считает токеном без `sub` — решения SDK не должны расходиться.
        return (claims.opt("sub") as? String)?.trim()?.takeIf { it.isNotEmpty() }
    }

    /** Ключ сравнения: `sub`, а у нечитаемого токена — сама строка (сравнивать больше нечего). */
    fun key(token: String): String = of(token) ?: token

    /**
     * Хеш ключа, привязанный к установке. На диск ложится он, а не `sub`: идентификатор
     * пользователя интегратора — его персональные данные, и в prefs SDK им делать нечего.
     * Привязка к `installationId` не даёт хешу пережить `reset()` в виде «того же человека».
     */
    fun hash(installationId: String, key: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$installationId\n$key".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * Решение по хешам: прежний (`null` — токена не было или был выход) и новый (`null` —
     * `identify(null)`).
     *
     * Выход — всегда выход: связь могла остаться с прошлого запуска. Свежий токен того же
     * человека ленту не трогает: хост выпускает его на каждый вход в чат, и сброс обрывал бы
     * стримящийся ответ. Другой человек без выхода — [IdentityChange.Switch]: только лента
     * здесь не спасает, сервер с устаревшим токеном нового оставил бы устройство за прежним.
     * Первый вход после анонима выход не шлёт: рвать нечего, а после обновления с 0.2.8, где
     * хеша ещё нет, это стёрло бы историю вошедшему заново тому же человеку.
     */
    fun change(previousHash: String?, newHash: String?): IdentityChange = when {
        newHash == null -> IdentityChange.Logout
        previousHash == null -> IdentityChange.SignIn
        previousHash == newHash -> IdentityChange.Refresh
        else -> IdentityChange.Switch
    }

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
