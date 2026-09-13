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

    /**
     * Рукопожатие перебито сменой пользователя. Для экрана безобидно, только если сменилась и
     * эпоха контроллера; иначе (попытки исчерпаны без смены человека) — провал отправки.
     */
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
                // Ключ приложения не от этого канала: чинится только правкой интеграции.
                errorCode == "channel_mismatch" -> R.string.meerbot_err_key_invalid
                errorCode == "platform_mismatch" -> R.string.meerbot_err_key_invalid
                errorCode == "mobile_app_inactive" || errorCode == "instance_disabled" ->
                    R.string.meerbot_err_channel_off
                // Деньги владельца приложения — не дело пользователя: ему важно только то,
                // что чат сейчас не ответит. Точная причина остаётся в `code` для логов.
                errorCode == "daily_budget_exceeded" ||
                    errorCode == "insufficient_balance" ||
                    errorCode == "wallet_unavailable" ||
                    errorCode == "quota_exceeded" -> R.string.meerbot_err_quota
                errorCode == "conversation_cap_reached" -> R.string.meerbot_err_conv_cap
                errorCode.startsWith("rate_limit") || errorCode == "rate_limited" ->
                    R.string.meerbot_err_rate_limited
                errorCode == "message_too_long" -> R.string.meerbot_err_message_too_long
                errorCode == "identity_required" -> R.string.meerbot_err_identity_required
                // Ответ на это же сообщение генерирует другой запрос (повтор после обрыва):
                // сообщение сохранено, ответ придёт догоном, «Повторить» безопасен.
                errorCode == GENERATION_IN_PROGRESS -> R.string.meerbot_err_generation_in_progress
                // До экрана такая ошибка доходит, только когда переподключение уже было и не
                // помогло (SDK повторяет его сам, один раз). «Переподключаемся…» здесь — неправда.
                isExpiredToken -> R.string.meerbot_err_session_lost
                status == 401 -> R.string.meerbot_err_session_expired
                else -> R.string.meerbot_err_server
            }

            is Network -> R.string.meerbot_err_network

            is Stream -> when (errorCode) {
                "ai_unavailable" -> R.string.meerbot_err_ai_unavailable
                GENERATION_IN_PROGRESS -> R.string.meerbot_err_generation_in_progress
                else -> R.string.meerbot_err_stream_broken
            }

            is InvalidResponse -> R.string.meerbot_err_invalid_response
            is Cancelled -> R.string.meerbot_err_cancelled
        }

    /**
     * Токен протух и запрос имеет смысл повторить ровно один раз.
     *
     * 401 с кодом из семейства `jwt_*`, а также `device_not_found` / `device_claim_missing`:
     * устройство из токена снято с регистрации (например, выход отвязал его на сервере), и
     * новое рукопожатие заведёт действующее. Раньше эти коды показывали «сессия истекла» без
     * самого переподключения. Отказ по каналу (`channel_mismatch`) сюда не попадает: канал
     * заявлен в самом ключе, и новый токен будет ровно таким же — сервер поэтому и отдаёт его
     * как 403, а не 401.
     */
    val isExpiredToken: Boolean
        get() = this is Http && status == 401 &&
            (errorCode.startsWith("jwt_") || errorCode == "device_not_found" || errorCode == "device_claim_missing")
}

/** Ответ на это сообщение генерирует другой запрос. Вне класса: публичный API ошибки не растёт. */
private const val GENERATION_IN_PROGRESS = "generation_in_progress"
