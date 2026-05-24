package ru.meerbot.sdk.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * MeerBot Android SDK — Phase 5.c: state machine для ChatScreen.
 * Контракт идентичен iOS ChatStore и RN reducer.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,               // "user" | "assistant" | "system"
    val author: String? = null,     // "ai" | "manager" | null
    val authorName: String? = null,
    val content: String,
    val streaming: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
)

enum class ChatMode(val raw: String) {
    Ai("ai"),
    PendingEscalation("pending_escalation"),
    Human("human"),
    Closed("closed"),
}

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val mode: ChatMode = ChatMode.Ai,
    val operatorTyping: String? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val connectionError: String? = null,
)

class ChatViewModel : ViewModel() {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    fun setDraft(text: String) {
        _state.update { it.copy(draft = text) }
    }

    fun clearDraft() {
        _state.update { it.copy(draft = "") }
    }

    fun setMode(mode: ChatMode) {
        _state.update { it.copy(mode = mode) }
    }

    fun setOperatorTyping(name: String?) {
        _state.update { it.copy(operatorTyping = name) }
    }

    fun setError(error: String?) {
        _state.update { it.copy(connectionError = error) }
    }

    fun setSending(value: Boolean) {
        _state.update { it.copy(sending = value) }
    }

    fun appendUserMessage(content: String): ChatMessage {
        val msg = ChatMessage(role = "user", content = content)
        _state.update { it.copy(messages = it.messages + msg) }
        return msg
    }

    fun appendAssistantPlaceholder(): ChatMessage {
        val msg = ChatMessage(role = "assistant", author = "ai", content = "", streaming = true)
        _state.update { it.copy(messages = it.messages + msg) }
        return msg
    }

    fun updateAssistantContent(id: String, delta: String) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { m ->
                    if (m.id == id) m.copy(content = m.content + delta) else m
                }
            )
        }
    }

    fun finalizeAssistant(id: String) {
        _state.update { current ->
            current.copy(
                messages = current.messages.map { m ->
                    if (m.id == id) m.copy(streaming = false) else m
                }
            )
        }
    }

    fun appendOperatorMessage(content: String, authorName: String?) {
        val msg = ChatMessage(
            role = "assistant",
            author = "manager",
            authorName = authorName,
            content = content,
        )
        _state.update { it.copy(messages = it.messages + msg) }
    }

    fun resetForLogout() {
        _state.value = ChatState()
    }

    /**
     * Демонстрационный отправитель — Phase 5.c polish: реальная интеграция через
     * ApiClient.openChatStream() (Kotlin Flow), вызывается из MeerBot.configure().
     */
    fun sendDemo(text: String) {
        appendUserMessage(text)
        clearDraft()
        setSending(true)
        val placeholder = appendAssistantPlaceholder()
        viewModelScope.launch {
            kotlinx.coroutines.delay(600)
            updateAssistantContent(
                placeholder.id,
                "Это демо-ответ. Реальная интеграция через MeerBot.configure().",
            )
            finalizeAssistant(placeholder.id)
            setSending(false)
        }
    }
}
