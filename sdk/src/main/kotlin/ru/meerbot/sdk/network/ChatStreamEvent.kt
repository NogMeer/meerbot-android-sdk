package ru.meerbot.sdk.network

import org.json.JSONObject
import ru.meerbot.sdk.state.ChatMode

/**
 * Типизированные события чат-стрима.
 *
 * Соответствие бэкенду — `src/app/api/v1/widget/chat/stream/route.ts`:
 *   event: meta                 → {conversationId, mode, escalation}
 *   (без event)                 → OpenAI-совместимый чанк {choices:[{delta:{content}}]}
 *   data: [DONE]                → генерация AI завершена (стрим может остаться открытым
 *                                 ради ответов менеджера в режиме pending_escalation)
 *   event: manager_message      → {messageId, role, text, authorName, createdAt}
 *   event: escalation           → {triggered, reason, forumTopicId?}
 *   event: forwarded_to_manager → {mode}
 *   event: heartbeat            → {} каждые 15 с
 *   event: usage                → квота (адресовано хелп-виджету кабинета, не мобильному)
 *   event: error                → {code, message}
 *   event: timeout              → достигнут max lifetime (30 мин)
 *   event: shutdown             → плановый рестарт сервера, НЕ сетевой сбой
 *
 * Незнакомое событие — не ошибка: сервер расширяем без ломки клиентов.
 */
sealed class ChatStreamEvent {
    data class Meta(val conversationId: Long, val mode: ChatMode) : ChatStreamEvent()
    data class ContentDelta(val text: String) : ChatStreamEvent()
    object Done : ChatStreamEvent()
    data class Manager(val message: ManagerMessage) : ChatStreamEvent()
    data class Escalation(val triggered: Boolean, val reason: String) : ChatStreamEvent()
    data class ForwardedToManager(val mode: ChatMode) : ChatStreamEvent()
    object Heartbeat : ChatStreamEvent()
    data class ServerError(val code: String, val message: String) : ChatStreamEvent()
    object Timeout : ChatStreamEvent()
    data class Shutdown(val reason: String) : ChatStreamEvent()
    data class Unknown(val name: String) : ChatStreamEvent()

    companion object {
        /**
         * Отображение сырого SSE в типизированное событие. `null` — событие без полезной
         * нагрузки (пустой чанк, keep-alive), его нужно молча пропустить.
         */
        fun from(raw: SseEvent): ChatStreamEvent? {
            if (raw.data == "[DONE]") return Done

            val json = decode(raw.data)

            return when (raw.name) {
                SseReader.DEFAULT_EVENT_NAME -> {
                    val text = json
                        ?.optJSONArray("choices")
                        ?.optJSONObject(0)
                        ?.optJSONObject("delta")
                        ?.optString("content")
                        .orEmpty()
                    if (text.isEmpty()) null else ContentDelta(text)
                }

                "meta" -> Meta(
                    conversationId = json?.optLong("conversationId", -1L) ?: -1L,
                    mode = ChatMode.from(json?.optString("mode")),
                )

                "manager_message" -> {
                    val text = json?.optString("text").orEmpty()
                    if (text.isEmpty()) null else Manager(
                        ManagerMessage(
                            messageId = json?.optLong("messageId", 0L) ?: 0L,
                            text = text,
                            authorName = json?.optStringOrNull("authorName"),
                        )
                    )
                }

                "escalation" -> Escalation(
                    triggered = json?.optBoolean("triggered", true) ?: true,
                    reason = json?.optStringOrNull("reason") ?: "unknown",
                )

                "forwarded_to_manager" -> ForwardedToManager(
                    mode = ChatMode.from(json?.optString("mode"), fallback = ChatMode.PendingEscalation),
                )

                "heartbeat" -> Heartbeat

                "error" -> ServerError(
                    code = json?.optStringOrNull("code") ?: "server_error",
                    message = json?.optStringOrNull("message") ?: "Server error",
                )

                "timeout" -> Timeout

                "shutdown" -> Shutdown(json?.optStringOrNull("reason") ?: "server_restart")

                else -> Unknown(raw.name)
            }
        }

        private fun decode(raw: String): JSONObject? =
            if (raw.isEmpty()) null else runCatching { JSONObject(raw) }.getOrNull()

        private fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }
    }
}

/** Ответ менеджера, пришедший в открытый стрим. */
data class ManagerMessage(
    val messageId: Long,
    val text: String,
    val authorName: String?,
)
