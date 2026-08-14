package ru.meerbot.sdk.network

import androidx.annotation.StringRes
import ru.meerbot.sdk.R

/**
 * Ошибка SDK.
 *
 * `code` — машинный код платформы (`key_invalid`, `rate_limit_ip`, `quota_exceeded`,
 * `jwt_expired`, …) для аналитики и логов; [messageRes] — ресурс текста для пользователя,
 * поэтому текст следует локали устройства, а не языку разработчика.
 * Полный список кодов — docs/mobile-sdk/errors.md.
 */
sealed class MeerBotError(message: String) : Exception(message) {

    /** `MeerBot.configure(...)` не вызван либо вызван с пустым ключом. */
    object NotConfigured : MeerBotError("MeerBot не настроен") {
        private fun readResolve(): Any = NotConfigured
    }

    /** Сервер ответил кодом ≥400. */
    data class Http(
        val status: Int,
        val errorCode: String,
        val serverMessage: String,
    ) : MeerBotError("HTTP $status $errorCode: $serverMessage")

    /** Транспортная ошибка: нет сети, обрыв соединения, таймаут. */
    data class Network(val reason: String) : MeerBotError("Network: $reason")

    /** Ошибка внутри уже открытого потока (`event: error`). */
    data class Stream(val errorCode: String, val serverMessage: String) :
        MeerBotError("Stream $errorCode: $serverMessage")

    object InvalidResponse : MeerBotError("Неожиданный ответ сервера") {
        private fun readResolve(): Any = InvalidResponse
    }

    object Cancelled : MeerBotError("Отменено") {
        private fun readResolve(): Any = Cancelled
    }

    val code: String
        get() = when (this) {
            is NotConfigured -> "not_configured"
            is Http -> errorCode
            is Network -> "network_io"
            is Stream -> errorCode
            is InvalidResponse -> "invalid_response"
            is Cancelled -> "cancelled"
        }

    /** Ресурс текста для пользователя. */
    @get:StringRes
    val messageRes: Int
        get() = when (this) {
            is NotConfigured -> R.string.meerbot_err_not_configured

            is Http -> when {
                errorCode == "key_invalid" -> R.string.meerbot_err_key_invalid
                errorCode == "widget_not_active" -> R.string.meerbot_err_channel_off
                errorCode == "quota_exceeded" -> R.string.meerbot_err_quota
                errorCode == "conversation_cap_reached" -> R.string.meerbot_err_conv_cap
                errorCode.startsWith("rate_limit") || errorCode == "rate_limited" ->
                    R.string.meerbot_err_rate_limited
                status == 401 -> R.string.meerbot_err_session_expired
                else -> R.string.meerbot_err_server
            }

            is Network -> R.string.meerbot_err_network

            is Stream ->
                if (errorCode == "ai_unavailable") R.string.meerbot_err_ai_unavailable
                else R.string.meerbot_err_stream_broken

            is InvalidResponse -> R.string.meerbot_err_invalid_response
            is Cancelled -> R.string.meerbot_err_cancelled
        }

    /**
     * Токен протух и запрос имеет смысл повторить ровно один раз.
     *
     * Только 401 с кодом из семейства `jwt_*`: `jwt_channel_mismatch` тоже приходит с этим
     * префиксом, но повтор его не лечит — ключ перепутан, — поэтому он исключён явно.
     */
    val isExpiredToken: Boolean
        get() = this is Http &&
            status == 401 &&
            errorCode.startsWith("jwt_") &&
            errorCode != "jwt_channel_mismatch"
}
