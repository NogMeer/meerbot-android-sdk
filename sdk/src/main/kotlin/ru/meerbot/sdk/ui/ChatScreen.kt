package ru.meerbot.sdk.ui

import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.sp
import ru.meerbot.sdk.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import ru.meerbot.sdk.state.ChatController
import ru.meerbot.sdk.state.ChatMessage
import ru.meerbot.sdk.state.ChatMode

/**
 * Экран чата. Контракт совпадает с iOS ChatView.
 *
 * Цвета берутся только из [MaterialTheme.colorScheme] — тема хост-приложения (в том числе
 * тёмная и Material You) применяется автоматически, литеральных цветов в файле нет.
 */
@Composable
fun ChatScreen(
    controller: ChatController,
    modifier: Modifier = Modifier,
    title: String? = null,
    primaryColor: Color? = null,
    onClose: (() -> Unit)? = null,
) {
    val state by controller.state.collectAsState()
    val listState = rememberLazyListState()
    val accent = primaryColor ?: MaterialTheme.colorScheme.primary
    val listDescription = stringResource(R.string.meerbot_messages_list)

    // Протягивание переписки убирает клавиатуру — так ведёт себя любой мессенджер, и без
    // этого выйти из ввода нечем: своей кнопки «Готово» у поля нет, а хост-приложение
    // обычно показывает экран без панели действий. Зеркало iOS-поведения
    // (`scrollDismissesKeyboard` в ChatView).
    //
    // Реагируем только на палец (`Drag`): программная прокрутка к свежему сообщению не
    // должна закрывать клавиатуру человеку, который в этот момент печатает.
    val keyboardController = LocalSoftwareKeyboardController.current
    val dismissKeyboardOnScroll = remember(keyboardController) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (source == NestedScrollSource.Drag && available.y != 0f) {
                    keyboardController?.hide()
                }
                return Offset.Zero
            }
        }
    }

    // Handshake — на первом показе экрана, а не на старте приложения: иначе визитор
    // записывался бы каждому, кто чат ни разу не открыл.
    //
    // Наблюдатель жизненного цикла, а не `LaunchedEffect`: тот срабатывал ровно один раз
    // (ключ `controller` живёт в синглтоне SDK и не меняется), поэтому возврат приложения из
    // фона не догонял ленту, а `stop()` не звался ВООБЩЕ — догон крутился бы за закрытым
    // экраном. На iOS симметрия держится на `.onAppear`/`.onDisappear`.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(controller, lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // ON_START приходит и на первом показе, и при возврате из фона. `start()` сам
                // решает: рукопожатие или только догон.
                Lifecycle.Event.ON_START -> controller.start()
                Lifecycle.Event.ON_STOP -> controller.stop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            controller.stop()
        }
    }

    // Первая порция истории уже показана? До неё прыжок вниз делается БЕЗ анимации.
    //
    // История грузится асинхронно уже после открытия экрана, поэтому анимированный скролл
    // на ней читается как «чат открылся сверху и поехал вниз» — мессенджеры так себя не
    // ведут, переписка обязана открываться сразу на последнем сообщении. Анимация остаётся
    // там, где она уместна: новое сообщение в открытом чате. Зеркало iOS (`didInitialScroll`).
    // `remember`, а не `rememberSaveable`: при пересоздании экрана история перезагружается
    // с нуля, и мгновенный прыжок вниз там снова уместен.
    var didInitialScroll by remember { mutableStateOf(false) }

    LaunchedEffect(state.messages.size, state.messages.lastOrNull()?.content) {
        if (state.messages.isEmpty()) return@LaunchedEffect
        val lastIndex = state.messages.size - 1
        if (didInitialScroll) {
            listState.animateScrollToItem(lastIndex)
        } else {
            didInitialScroll = true
            // Без анимации — экран должен ОТКРЫТЬСЯ внизу, а не доехать туда.
            listState.scrollToItem(lastIndex)
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
                        .nestedScroll(dismissKeyboardOnScroll)
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
                onRetry = if (state.retryable != null) controller::retry else null,
            )
        }

        ChatInput(
            draft = state.draft,
            sending = state.sending,
            closed = state.mode == ChatMode.Closed,
            accent = accent,
            onDraftChange = controller::setDraft,
            onSend = controller::send,
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
    val notDelivered = stringResource(R.string.meerbot_not_delivered)
    // Пузырь читается вслух одной репликой: «Ваше сообщение: …», а не по кускам. Недоставку
    // включаем в ту же реплику — иначе о ней узнают только зрячие.
    val bubbleDescription = buildString {
        append(roleDescription)
        append(": ")
        append(message.content)
        if (message.failed) {
            append(", ")
            append(notDelivered)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .semantics(mergeDescendants = true) {
                contentDescription = bubbleDescription
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
                if (message.streaming && message.content.isEmpty()) {
                    // Модель ещё думает — текста нет вовсе. Раньше здесь оставался ОДИН
                    // символ `▍`, и пузырь выглядел как обрывок непонятного глифа: человек
                    // не понимал, ответ это или сбой. Три пульсирующие точки — то, чем
                    // «собеседник печатает» показывают все мессенджеры, объяснять их не надо.
                    TypingDots()
                } else {
                    // Текст уже пошёл — курсор в конце строки читается как курсор (так
                    // делают ChatGPT и Claude), но ТОЛЬКО мигающий: статичный символ в
                    // конце ответа неотличим от опечатки бота.
                    val cursor = if (message.streaming && blinkVisible()) "▍" else ""
                    Text(
                        message.content + cursor,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
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

    // Вертикальную метрику поле ввода задаёт САМО, а не берёт из typography хоста. Высота
    // каретки в BasicTextField равна высоте строки, а высота строки по умолчанию — метрики
    // шрифта; у дисплейных шрифтов (Gilroy и подобные) они шире букв в полтора раза, и курсор
    // торчит над и под текстом заметной синей палкой. Явный lineHeight по кеглю прижимает
    // строку к глифам, Trim.None не даёт срезать её обратно к метрикам, Alignment.Center
    // держит текст по центру пилюли.
    //
    // Пузырям сообщений эта метрика НЕ навязывается: там строки идут одна под другой, и
    // тесный интервал только слепил бы их — они остаются на typography темы.
    val baseTextStyle = MaterialTheme.typography.bodyMedium
    val fieldTextStyle = baseTextStyle.copy(
        lineHeight = baseTextStyle.fontSize.takeOrElse { 16.sp },
        lineHeightStyle = LineHeightStyle(
            alignment = LineHeightStyle.Alignment.Center,
            trim = LineHeightStyle.Trim.None,
        ),
    )

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
                textStyle = fieldTextStyle.copy(
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
                            // Плейсхолдер идёт той же метрикой, что и сам ввод: иначе он
                            // встаёт на другую базовую линию и прыгает при первом символе.
                            style = fieldTextStyle,
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
/**
 * Фаза мигания курсора при стриминге: полсекунды виден, полсекунды нет.
 *
 * При системной «отключить анимации» курсор показывается ПОСТОЯННО, а не пропадает:
 * признак «ответ ещё пишется» нужен и там, мигание — лишь способ его подать.
 */
@Composable
private fun blinkVisible(): Boolean {
    if (animationsDisabled()) return true
    val transition = rememberInfiniteTransition(label = "meerbot-cursor")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 2f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
        ),
        label = "meerbot-cursor-phase",
    )
    return phase < 1f
}

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
