package ru.meerbot.sdk.network

import android.content.Context
import ru.meerbot.sdk.BuildConfig

/**
 * Настройки SDK.
 *
 * ── Какой контракт настоящий (сверено по коду agentbot-platform) ──────────────────────────
 *
 * ЧАТ живёт в `/api/v1/widget/…` и нигде больше:
 *   POST /api/v1/widget/session      — handshake pk_live_* + Origin → JWT (15 мин)
 *   POST /api/v1/widget/chat/stream  — SSE-поток ответа (тело: {message, conversationId?})
 *   GET  /api/v1/widget/messages     — догон истории после обрыва (?conversationId&since)
 *
 * `/api/v1/mobile/…` — только регистрация устройства и аттестация, эндпоинта чата там нет:
 *   POST /api/v1/mobile/register     — upsert MobileDevice(FCM-токен) → JWT
 *
 * Отсюда два ключа: JWT из `/mobile/register` подписан на пространство id мобильного
 * приложения и чат-эндпоинтом не принимается. Поэтому чат ходит по ключу headless-виджета,
 * а пуши — по ключу мобильного приложения. Ровно эта ошибка была в первой версии клиента.
 */
data class MeerBotConfiguration(
    /** `pk_live_*` headless-виджета (кабинет → Бот → Каналы). Транспорт чата. */
    val apiKey: String,
    /** `pk_live_*` мобильного приложения. Нужен только для регистрации FCM-токена. */
    val pushApiKey: String? = null,
    val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * Значение заголовка `Origin`. Обязано входить в «разрешённые домены» ключа, иначе
     * handshake вернёт `401 key_invalid`. Дефолт — `https://<applicationId>`.
     *
     * Почему https, а не `mobile://`: кабинет принимает в разрешённые домены только
     * https-origin (`isValidOriginPattern` в api/client/widget/route.ts), схему `mobile://`
     * туда физически не вписать.
     */
    val origin: String,
    val sdkVersion: String = BuildConfig.SDK_VERSION,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://meerbot.ru"

        fun defaultOrigin(context: Context): String = "https://${context.packageName}"
    }
}
