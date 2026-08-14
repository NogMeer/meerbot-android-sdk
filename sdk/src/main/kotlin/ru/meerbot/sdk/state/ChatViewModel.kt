package ru.meerbot.sdk.state

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.meerbot.sdk.MeerBot

/**
 * Тонкая обёртка над [ChatController] для Compose-экрана.
 *
 * Вся логика — в контроллере: ViewModel нужна только чтобы `viewModel()` пережил поворот
 * экрана. Контроллер переживает и её: он живёт в [MeerBot] столько же, сколько сессия SDK,
 * иначе поворот экрана рвал бы открытый SSE-поток.
 */
class ChatViewModel(
    controllerProvider: () -> ChatController? = { MeerBot.chatController() },
) : ViewModel() {

    private val controller: ChatController? = controllerProvider()

    /** Пустое состояние на случай, если `configure()` не вызван — экран покажет заглушку. */
    private val fallback = MutableStateFlow(ChatState()).asStateFlow()

    val state: StateFlow<ChatState> = controller?.state ?: fallback

    fun start() = controller?.start() ?: Unit

    fun setDraft(text: String) = controller?.setDraft(text) ?: Unit

    fun send(text: String) = controller?.send(text) ?: Unit

    fun retry() = controller?.retry() ?: Unit

    // stop() сознательно не вызывается в onCleared(): ViewModel умирает при повороте экрана,
    // а поток должен пережить его. Останавливает поток тот, кто закрывает чат.
}
