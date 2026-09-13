package ru.meerbot.sdk.network

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.security.MessageDigest

/** Что означает очередной `identify` по сравнению с последним применённым. */
internal enum class IdentityChange {
    /** Свежий токен того же `sub`. Лента не трогается, флаг выхода не ставится. */
    Refresh,

    /**
     * Любой другой токен: другой `sub`, первый вход, вход после выхода, прежний `sub` неизвестен
     * или нечитаем. Выход + вход одним рукопожатием, лента очищается.
     */
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
     * Решение по хешам: прежний (`null` — токена не было, был выход, либо хеш не прочитался) и
     * новый (`null` — `identify(null)`).
     *
     * Выход — всегда выход: связь могла остаться с прошлого запуска. [IdentityChange.Refresh] —
     * ТОЛЬКО когда известно, что это тот же человек: `sub` нового токена читается и его хеш
     * совпал с сохранённым. Хост выпускает свежий токен на каждый вход в чат, и сброс обрывал бы
     * стримящийся ответ.
     *
     * Всё остальное — [IdentityChange.Switch], с флагом выхода, в том числе когда прежнего хеша
     * нет. Устройство могло остаться привязанным к человеку, которого SDK не знает: вход на
     * 0.2.8 (хеша тогда не было) или нечитаемый хеш. Без выхода сервер, отклонив токен нового
     * (просрочен, исчерпан лимит, не настроен секрет), оставил бы устройство за прежним — и
     * новый увидел бы чужой тред. Выход тут ничего не ломает (сервер с `fe012073`): тот же `sub`
     * со свежим или устаревшим токеном устройство сохраняет, свежий повторный вход возвращает
     * тред, аноним с выходом остаётся как есть.
     */
    fun change(previousHash: String?, newHash: String?, newSubjectReadable: Boolean): IdentityChange = when {
        newHash == null -> IdentityChange.Logout
        newSubjectReadable && previousHash != null && previousHash == newHash -> IdentityChange.Refresh
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
