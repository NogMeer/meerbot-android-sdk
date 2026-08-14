package ru.meerbot.sdk.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ru.meerbot.sdk.R
import ru.meerbot.sdk.state.ChatMessage
import ru.meerbot.sdk.state.ChatMode
import ru.meerbot.sdk.state.ChatViewModel

/**
 * Экран чата. Контракт совпадает с iOS ChatView.
 *
 * Цвета берутся только из [MaterialTheme.colorScheme] — тема хост-приложения (в том числе
 * тёмная и Material You) применяется автоматически, литеральных цветов в файле нет.
 */
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    title: String? = null,
    primaryColor: Color? = null,
    onClose: (() -> Unit)? = null,
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val accent = primaryColor ?: MaterialTheme.colorScheme.primary
    val listDescription = stringResource(R.string.meerbot_messages_list)

    // Handshake — на первом показе экрана, а не на старте приложения: иначе визитор
    // записывался бы каждому, кто чат ни разу не открыл.
    LaunchedEffect(Unit) { viewModel.start() }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem(state.messages.size - 1)
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        ChatHeader(
            title = title ?: stringResource(R.string.meerbot_chat_title),
            onClose = onClose,
        )
        HorizontalDivider()

        Box(modifier = Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                EmptyState(
                    text = state.greeting
                        ?: stringResource(
                            if (state.ready) R.string.meerbot_empty_greeting
                            else R.string.meerbot_connecting
                        ),
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .semantics {
                            contentDescription = listDescription
                        },
                    contentPadding = PaddingValues(vertical = 8.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message = message, accent = accent)
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = state.operatorTyping != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(),
        ) {
            TypingRow(name = state.operatorTyping.orEmpty())
        }

        state.connectionError?.let { error ->
            ConnectionBanner(
                text = stringResource(error.messageRes),
                onRetry = if (state.retryable != null) viewModel::retry else null,
            )
        }

        ChatInput(
            draft = state.draft,
            sending = state.sending,
            closed = state.mode == ChatMode.Closed,
            accent = accent,
            onDraftChange = viewModel::setDraft,
            onSend = viewModel::send,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatHeader(title: String, onClose: (() -> Unit)?) {
    TopAppBar(
        title = { Text(title, fontWeight = FontWeight.SemiBold) },
        actions = {
            if (onClose != null) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.meerbot_close),
                    )
                }
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
    )
}

@Composable
private fun EmptyState(text: String) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, accent: Color) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) accent else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val roleDescription = stringResource(
        if (isUser) R.string.meerbot_message_from_you else R.string.meerbot_message_from_bot
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            // Пузырь читается вслух одной репликой: «Ваше сообщение: …», а не по кускам.
            .clearAndSetSemantics {
                contentDescription = "$roleDescription: ${message.content}"
            },
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Column(
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 16.dp,
                            topEnd = 16.dp,
                            bottomStart = if (isUser) 16.dp else 4.dp,
                            bottomEnd = if (isUser) 4.dp else 16.dp,
                        )
                    )
                    .background(bubbleColor)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            ) {
                if (message.author == "manager" && message.authorName != null) {
                    Text(
                        message.authorName,
                        color = textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.labelSmall,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                }
                Text(
                    message.content + if (message.streaming) " ▍" else "",
                    color = textColor,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (message.failed) {
                Text(
                    stringResource(R.string.meerbot_not_delivered),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun TypingRow(name: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TypingDots()
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            stringResource(R.string.meerbot_typing, name),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelMedium,
        )
    }
}

@Composable
private fun TypingDots() {
    val dotColor = MaterialTheme.colorScheme.onSurfaceVariant
    if (animationsDisabled()) {
        // Системная «отключить анимации» уважается: три статичные точки вместо пульсации.
        Row(verticalAlignment = Alignment.CenterVertically) {
            repeat(3) { index ->
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                if (index < 2) Spacer(modifier = Modifier.width(4.dp))
            }
        }
        return
    }

    val transition = rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { index ->
            val alpha by transition.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 500, delayMillis = index * 150),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "dot_$index",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(dotColor.copy(alpha = alpha)),
            )
            if (index < 2) Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ConnectionBanner(text: String, onRetry: (() -> Unit)?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.errorContainer)
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            modifier = Modifier.weight(1f),
            color = MaterialTheme.colorScheme.onErrorContainer,
            style = MaterialTheme.typography.bodySmall,
        )
        if (onRetry != null) {
            TextButton(onClick = onRetry) {
                Text(
                    stringResource(R.string.meerbot_retry),
                    color = MaterialTheme.colorScheme.onErrorContainer,
                )
            }
        }
    }
}

@Composable
private fun ChatInput(
    draft: String,
    sending: Boolean,
    closed: Boolean,
    accent: Color,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    val canSend = draft.isNotBlank() && !sending && !closed
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Box(modifier = Modifier.weight(1f)) {
            BasicTextField(
                value = draft,
                onValueChange = onDraftChange,
                enabled = !closed,
                textStyle = MaterialTheme.typography.bodyMedium.copy(
                    color = MaterialTheme.colorScheme.onSurface,
                ),
                cursorBrush = androidx.compose.ui.graphics.SolidColor(accent),
                maxLines = 5,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                decorationBox = { inner ->
                    if (draft.isEmpty()) {
                        Text(
                            stringResource(
                                if (closed) R.string.meerbot_input_hint_closed
                                else R.string.meerbot_input_hint
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    inner()
                },
            )
        }
        Spacer(modifier = Modifier.width(8.dp))
        FilledIconButton(
            onClick = { if (canSend) onSend(draft) },
            enabled = canSend,
            modifier = Modifier.size(48.dp),
            colors = IconButtonDefaults.filledIconButtonColors(
                containerColor = accent,
                contentColor = MaterialTheme.colorScheme.onPrimary,
            ),
        ) {
            Icon(Icons.Default.Send, contentDescription = stringResource(R.string.meerbot_send))
        }
    }
}

/** Системная настройка «убрать анимации» (Специальные возможности → Удалить анимацию). */
@Composable
private fun animationsDisabled(): Boolean {
    val context = LocalContext.current
    return remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f,
        ) == 0f
    }
}
