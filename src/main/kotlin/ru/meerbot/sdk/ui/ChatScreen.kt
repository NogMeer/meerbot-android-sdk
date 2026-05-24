package ru.meerbot.sdk.ui

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Send
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import ru.meerbot.sdk.state.ChatMessage
import ru.meerbot.sdk.state.ChatMode
import ru.meerbot.sdk.state.ChatViewModel

/**
 * MeerBot Android SDK — Phase 5.c: основной экран чата.
 * Контракт идентичен iOS ChatView и RN ChatScreen.
 */
@Composable
fun ChatScreen(
    modifier: Modifier = Modifier,
    title: String = "Поддержка",
    primaryColor: Color = MaterialTheme.colorScheme.primary,
    onClose: () -> Unit = {},
    viewModel: ChatViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text(title, fontWeight = FontWeight.SemiBold) },
            actions = {
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Закрыть чат")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = MaterialTheme.colorScheme.surface,
            ),
        )
        HorizontalDivider()
        Box(modifier = Modifier.weight(1f)) {
            if (state.messages.isEmpty()) {
                Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text("💬", fontSize = 40.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Привет! Чем могу помочь?",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                    items(state.messages, key = { it.id }) { msg ->
                        MessageBubble(message = msg, primaryColor = primaryColor)
                    }
                }
                LaunchedEffect(state.messages.size) {
                    if (state.messages.isNotEmpty()) {
                        scope.launch { listState.animateScrollToItem(state.messages.size - 1) }
                    }
                }
            }
        }
        AnimatedVisibility(
            visible = state.operatorTyping != null,
            enter = fadeIn() + expandVertically(),
            exit = fadeOut(),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TypingDots()
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${state.operatorTyping} печатает…",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp,
                )
            }
        }
        state.connectionError?.let { err ->
            Text(
                err,
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.errorContainer)
                    .padding(8.dp),
                color = MaterialTheme.colorScheme.onErrorContainer,
                fontSize = 12.sp,
            )
        }
        ChatInput(
            draft = state.draft,
            sending = state.sending,
            closed = state.mode == ChatMode.Closed,
            primaryColor = primaryColor,
            onDraftChange = viewModel::setDraft,
            onSend = { viewModel.sendDemo(it) },
        )
    }
}

@Composable
private fun MessageBubble(message: ChatMessage, primaryColor: Color) {
    val isUser = message.role == "user"
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(
                    if (isUser) primaryColor else MaterialTheme.colorScheme.surfaceVariant,
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            if (message.author == "manager" && message.authorName != null) {
                Text(
                    message.authorName,
                    color = if (isUser) Color.White.copy(alpha = 0.7f) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp,
                )
            }
            Text(
                message.content + if (message.streaming) " ▍" else "",
                color = if (isUser) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun TypingDots() {
    val infiniteTransition = androidx.compose.animation.core.rememberInfiniteTransition(label = "typing")
    Row(verticalAlignment = Alignment.CenterVertically) {
        for (i in 0..2) {
            val scale by infiniteTransition.animateFloat(
                initialValue = 0.6f,
                targetValue = 1.0f,
                animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                    animation = androidx.compose.animation.core.tween(durationMillis = 500, delayMillis = i * 150),
                    repeatMode = androidx.compose.animation.core.RepeatMode.Reverse,
                ),
                label = "dot_$i",
            )
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = scale)),
            )
            if (i < 2) Spacer(modifier = Modifier.width(4.dp))
        }
    }
}

@Composable
private fun ChatInput(
    draft: String,
    sending: Boolean,
    closed: Boolean,
    primaryColor: Color,
    onDraftChange: (String) -> Unit,
    onSend: (String) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraftChange,
            enabled = !closed,
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(18.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            decorationBox = { inner ->
                if (draft.isEmpty()) {
                    Text(
                        "Сообщение…",
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
                inner()
            },
        )
        Spacer(modifier = Modifier.width(8.dp))
        FilledIconButton(
            onClick = {
                val trimmed = draft.trim()
                if (trimmed.isNotEmpty() && !sending && !closed) onSend(trimmed)
            },
            enabled = draft.trim().isNotEmpty() && !sending && !closed,
            colors = IconButtonDefaults.filledIconButtonColors(containerColor = primaryColor),
        ) {
            Icon(Icons.Default.Send, contentDescription = "Отправить", tint = Color.White)
        }
    }
}
