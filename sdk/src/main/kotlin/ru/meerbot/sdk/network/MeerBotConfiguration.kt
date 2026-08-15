package ru.meerbot.sdk.network

import ru.meerbot.sdk.BuildConfig

/**
 * Настройки SDK.
 *
 * ── Один ключ и свой канал ────────────────────────────────────────────────────────────
 *
 * Мобильное приложение — полноценный канал платформы (`mobile_app`), а не веб-виджет,
 * притворяющийся приложением. Весь обмен идёт по `/api/v1/mobile/…`:
 *
 *   POST /api/v1/mobile/register     — рукопожатие: ключ приложения → JWT канала (15 мин)
 *   POST /api/v1/mobile/chat/stream  — SSE-поток ответа (тело: {message})
 *   GET  /api/v1/mobile/messages     — догон истории после обрыва (?since&limit)
 *
 * Отсюда три следствия, которых не было у виджетной схемы:
 *   • ключ ровно один — `pk_live_*` мобильного приложения (Кабинет → Бот → Каналы →
 *     Мобильные приложения). Ключ виджета здесь не нужен и не подойдёт: JWT несёт claim
 *     канала, и чат-роут виджета отвергнет его как `channel_mismatch`;
 *   • заголовок `Origin` не отправляется. У нативного приложения его нет физически, и
 *     выдумывать `https://<applicationId>`, чтобы пройти проверку доменов веб-виджета,
 *     больше не требуется — «разрешённые домены» к каналу не относятся;
 *   • `conversationId` клиент НЕ выбирает: сервер резолвит диалог по устройству. Значение
 *     приходит в событии `meta` и полезно только чтобы хост-приложение могло подавить
 *     собственный пуш о диалоге, открытом на экране.
 */
data class MeerBotConfiguration(
    /** `pk_live_*` мобильного приложения из кабинета. */
    val apiKey: String,
    val baseUrl: String = DEFAULT_BASE_URL,
    val sdkVersion: String = BuildConfig.SDK_VERSION,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://meerbot.ru"
    }
}

/** Что сервер сделал с verified identity на последнем рукопожатии. */
enum class IdentityStatus(val raw: String) {
    /** Токен не передавали — пользователь анонимен. */
    NotProvided("not_provided"),
    Verified("verified"),
    /** У приложения не настроен секрет подписи — в кабинете нужно его задать. */
    NotConfigured("not_configured"),
    /** Токен просрочен: у интегратора истёк срок жизни `iat`. */
    Stale("stale"),
    Rejected("rejected");

    companion object {
        fun from(raw: String?): IdentityStatus =
            entries.firstOrNull { it.raw == raw } ?: NotProvided
    }
}
