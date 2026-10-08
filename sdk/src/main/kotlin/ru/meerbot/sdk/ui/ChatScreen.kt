package ru.meerbot.sdk.ui

import android.net.Uri
import android.provider.OpenableColumns
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.takeOrElse
import androidx.compose.ui.unit.sp
import ru.meerbot.sdk.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.meerbot.sdk.state.ChatController
import ru.meerbot.sdk.state.ChatMessage
import ru.meerbot.sdk.state.ChatMode
import ru.meerbot.sdk.state.OutgoingAttachment

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
    /**
     * Показывать ли шапку экрана (заголовок + крестик).
     *
     * `false` — когда чат открыт как вкладка хоста: там свой заголовок уже есть в его
     * навигации, и вторая полоса с тем же словом занимает высоту ни за чем. По умолчанию
     * шапка на месте: SDK чаще открывают модально, и без неё экран остаётся без названия
     * и без выхода.
     */
    showHeader: Boolean = true,
) {
    val state by controller.state.collectAsState()
    val listState = rememberLazyListState()
    val accent = primaryColor ?: MaterialTheme.colorScheme.primary
    val listDescription = stringResource(R.string.meerbot_messages_list)

    // ─── Вложения композера ───────────────────────────────────────────────────────────────
    val context = LocalContext.current
    val chatScope = rememberCoroutineScope()
    // Выбранные, но ещё не отправленные файлы. `remember`, а не `rememberSaveable`: Uri из
    // пикера действителен, пока живёт процесс; при пересоздании экрана выбор проще повторить,
    // чем восстанавливать разрешение на Uri.
    var pending by remember { mutableStateOf<List<PendingAttachment>>(emptyList()) }
    val limitToast = stringResource(R.string.meerbot_attach_limit, ChatController.MAX_ATTACHMENTS)
    val readFailedToast = stringResource(R.string.meerbot_attach_read_failed)

    fun addPicked(uris: List<Uri>) {
        if (uris.isEmpty()) return
        val remaining = ChatController.MAX_ATTACHMENTS - pending.size
        if (remaining <= 0) {
            Toast.makeText(context, limitToast, Toast.LENGTH_SHORT).show()
            return
        }
        val accepted = uris.take(remaining).map { readAttachmentMeta(context, it) }
        pending = pending + accepted
        // Выбрали больше, чем влезает — честно говорим, а не отбрасываем молча.
        if (uris.size > remaining) Toast.makeText(context, limitToast, Toast.LENGTH_SHORT).show()
    }

    // Photo Picker: картинки и видео без разрешений (Android 13+ и системный бэкпорт).
    val pickMedia = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia(ChatController.MAX_ATTACHMENTS),
    ) { uris -> addPicked(uris) }
    // Прочие файлы: system picker, тоже без разрешений на чтение хранилища.
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { addPicked(listOf(it)) } }

    fun submit() {
        val text = state.draft
        val picks = pending.toList()
        if (picks.isEmpty()) {
            controller.send(text)
            return
        }
        chatScope.launch {
            val outgoing = withContext(Dispatchers.IO) {
                picks.map { p -> readOutgoing(context, p) }
            }
            // Хоть один файл не прочитался (отозван доступ, слишком большой) — не отправляем
            // ничего: текст и вложения не теряем, показываем ошибку, повтор — ещё один тап Send.
            if (outgoing.any { it == null }) {
                Toast.makeText(context, readFailedToast, Toast.LENGTH_LONG).show()
                return@launch
            }
            controller.send(text, outgoing.filterNotNull())
            pending = emptyList()
        }
    }

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

    // Ключ — ПОСЛЕДНЕЕ сообщение, а не размер ленты: подгрузка старых сверху меняет размер,
    // но не должна сбрасывать читающего историю вниз.
    LaunchedEffect(state.messages.lastOrNull()?.id, state.messages.lastOrNull()?.content) {
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

    // Подгрузка старых: верх ленты (первые [OLDER_PREFETCH_ITEMS] строк) в зоне видимости.
    // Позицию при вставке сверху держит сам LazyColumn — строки с ключами, первая видимая
    // остаётся на месте. Эффект перезапускается по окончании загрузки: если лента всё ещё
    // короче экрана (верх виден), грузится следующая страница. После ошибки — только по
    // «Повторить», автоповтора нет.
    LaunchedEffect(listState, didInitialScroll, state.hasOlder, state.loadingOlder, state.olderFailed) {
        if (!didInitialScroll || !state.hasOlder || state.loadingOlder || state.olderFailed) return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex }
            .collect { first -> if (first < OLDER_PREFETCH_ITEMS) controller.loadOlder() }
    }

    // Лента едет ВМЕСТЕ с клавиатурой, кадр в кадр.
    //
    // Поле ввода поднимается на высоту вставки (`imePadding` в `ChatInput`), область списка на
    // столько же укорачивается, а прокрутка сама по себе не меняется: на выезде нижние
    // сообщения уходят под поле, на уходе лента остаётся задранной.
    //
    // Прокручиваем на РАЗНИЦУ вставки, а не «до последнего элемента»: система отдаёт высоту
    // на каждом кадре анимации, и сдвиг на дельту оставляет содержимое неподвижным
    // относительно клавиатуры — то есть плавным. Прыжок «доехать до низа» одним куском (хоть
    // в начале анимации, хоть в конце) виден именно как рывок.
    //
    // Дельта, а не «прижать низ», ещё и потому, что человек может читать историю выше: его
    // место сохраняется, лента просто не уезжает под клавиатуру.
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var previousImeBottom by remember { mutableIntStateOf(imeBottom) }
    LaunchedEffect(imeBottom) {
        val delta = imeBottom - previousImeBottom
        previousImeBottom = imeBottom
        if (delta == 0) return@LaunchedEffect

        val lastIndex = state.messages.lastIndex
        if (lastIndex >= 0 && !listState.canScrollForward) {
            // Лента стоит В САМОМ НИЗУ — держим низ, а не сдвигаем на дельту. У нижней
            // границы сдвиг упирается в конец списка: часть хода пропадает молча, и на
            // обратном ходе (клавиатура уходит) полная дельта уносит ленту ВЫШЕ последнего
            // сообщения. На выезде хватает прижать конец, на уходе низ остаётся низом сам —
            // список пересчитывает предел прокрутки под выросшую высоту.
            if (delta > 0) listState.scrollToItem(lastIndex)
            return@LaunchedEffect
        }

        listState.scrollBy(delta.toFloat())
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        if (showHeader) {
            ChatHeader(
                title = title ?: stringResource(R.string.meerbot_chat_title),
                onClose = onClose,
            )
            HorizontalDivider()
        }

        Box(
            modifier = Modifier
                .weight(1f)
                // Тап по переписке убирает клавиатуру — в пару к «протянул = закрыл» выше.
                // Без него выйти из ввода нечем: своей кнопки «Готово» у поля нет, а хост
                // обычно показывает экран без панели действий. `detectTapGestures` забирает
                // только тап, прокрутка списка внутри продолжает работать.
                .pointerInput(keyboardController) {
                    detectTapGestures(onTap = { keyboardController?.hide() })
                },
        ) {
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
                        MessageBubble(controller = controller, message = message, accent = accent)
                    }
                }
                // Статус подгрузки — поверх ленты, а не строкой списка: строка сдвигала бы
                // индексы, к которым привязаны доскролл и сдвиг под клавиатуру.
                OlderHistoryStatus(
                    loading = state.loadingOlder,
                    failed = state.olderFailed,
                    onRetry = controller::loadOlder,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                )
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
            pending = pending,
            canAttach = pending.size < ChatController.MAX_ATTACHMENTS,
            onDraftChange = controller::setDraft,
            onPickMedia = {
                pickMedia.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            },
            onPickFile = { pickFile.launch("*/*") },
            onRemovePending = { p -> pending = pending.filterNot { it === p } },
            onSend = { submit() },
        )
    }
}

/** Выбранный, но ещё не загруженный файл: только Uri и метаданные для чипа. Байты читаются на Send. */
private data class PendingAttachment(
    val uri: Uri,
    val fileName: String,
    val size: Long,
    val mime: String,
) {
    val isImage: Boolean get() = mime.startsWith("image/")
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
private fun MessageBubble(controller: ChatController, message: ChatMessage, accent: Color) {
    val isUser = message.role == "user"
    val bubbleColor = if (isUser) accent else MaterialTheme.colorScheme.surfaceVariant
    val textColor =
        if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val roleDescription = stringResource(
        if (isUser) R.string.meerbot_message_from_you else R.string.meerbot_message_from_bot
    )
    val notDelivered = stringResource(R.string.meerbot_not_delivered)
    val attachmentLabel = stringResource(R.string.meerbot_attachment_generic)
    val hasText = message.content.isNotEmpty()
    // Пузырь читается вслух одной репликой: «Ваше сообщение: …», а не по кускам. Вложения и
    // недоставку включаем в ту же реплику — иначе о них узнают только зрячие.
    val bubbleDescription = buildString {
        append(roleDescription)
        append(": ")
        if (hasText) append(message.content)
        message.attachments.forEach {
            if (isNotEmpty() && last() != ' ') append(" ")
            append(attachmentLabel)
            append(" ")
            append(it.fileName)
        }
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
                if (message.attachments.isNotEmpty()) {
                    MessageAttachments(
                        controller = controller,
                        message = message,
                        onSurface = textColor,
                        accent = accent,
                    )
                    if (hasText || message.streaming) Spacer(modifier = Modifier.height(6.dp))
                }
                if (message.streaming && message.content.isEmpty() && message.attachments.isEmpty()) {
                    // Модель ещё думает — текста нет вовсе. Раньше здесь оставался ОДИН
                    // символ `▍`, и пузырь выглядел как обрывок непонятного глифа: человек
                    // не понимал, ответ это или сбой. Три пульсирующие точки — то, чем
                    // «собеседник печатает» показывают все мессенджеры, объяснять их не надо.
                    TypingDots()
                } else if (hasText || message.streaming) {
                    // Текст уже пошёл — курсор в конце строки читается как курсор (так
                    // делают ChatGPT и Claude), но ТОЛЬКО мигающий: статичный символ в
                    // конце ответа неотличим от опечатки бота. Пустой текст при непустом
                    // вложении (сообщение только с файлом) строку не рисует вовсе.
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
    pending: List<PendingAttachment>,
    canAttach: Boolean,
    onDraftChange: (String) -> Unit,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
    onRemovePending: (PendingAttachment) -> Unit,
    onSend: () -> Unit,
) {
    val canSend = (draft.isNotBlank() || pending.isNotEmpty()) && !sending && !closed

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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .navigationBarsPadding()
            .imePadding(),
    ) {
        if (pending.isNotEmpty()) {
            PendingAttachmentsStrip(pending = pending, onRemove = onRemovePending)
        }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        AttachButton(
            enabled = canAttach && !sending && !closed,
            accent = accent,
            onPickMedia = onPickMedia,
            onPickFile = onPickFile,
        )
        Spacer(modifier = Modifier.width(4.dp))
        // Пилюля и её минимальная высота живут на РОДИТЕЛЕ, а поле центрируется внутри.
        // Когда `heightIn(min = 48.dp)` стоял на самом BasicTextField, поле растягивалось до
        // 48dp, но текст оставался наверху — остаток высоты уходил целиком под строку, и она
        // висела выше центра пилюли на пару dp. Теперь лишнее делится поровну, а на длинном
        // тексте (до 5 строк) пилюля растёт от содержимого, как и раньше.
        Box(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = 48.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.CenterStart,
        ) {
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
            onClick = { if (canSend) onSend() },
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
}

/** Кнопка-скрепка с меню выбора источника: «Фото и видео» (Photo Picker) или «Файл». */
@Composable
private fun AttachButton(
    enabled: Boolean,
    accent: Color,
    onPickMedia: () -> Unit,
    onPickFile: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Box {
        IconButton(
            onClick = { menuOpen = true },
            enabled = enabled,
            modifier = Modifier.size(48.dp),
        ) {
            Icon(
                imageVector = remember { attachIcon() },
                contentDescription = stringResource(R.string.meerbot_attach),
                tint = if (enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
            )
        }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meerbot_attach_media)) },
                onClick = {
                    menuOpen = false
                    onPickMedia()
                },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.meerbot_attach_file)) },
                onClick = {
                    menuOpen = false
                    onPickFile()
                },
            )
        }
    }
}

/** Горизонтальная лента чипов выбранных вложений над полем ввода. */
@Composable
private fun PendingAttachmentsStrip(
    pending: List<PendingAttachment>,
    onRemove: (PendingAttachment) -> Unit,
) {
    val removeLabel = stringResource(R.string.meerbot_attach_remove)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        pending.forEach { item ->
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(start = 10.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.widthIn(max = 160.dp)) {
                    Text(
                        item.fileName,
                        color = MaterialTheme.colorScheme.onSurface,
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (item.size > 0) {
                        Text(
                            humanSize(item.size),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                IconButton(onClick = { onRemove(item) }, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = removeLabel,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp),
                    )
                }
            }
        }
    }
}

// ─── Чтение выбранных файлов ─────────────────────────────────────────────────────────────────

/** Потолок чтения одного файла в память (25 МБ): защита от OOM на гигантском видео. */
private const val MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024

/** Метаданные файла из `ContentResolver` для чипа (имя, размер, mime). Быстро, на главном потоке. */
private fun readAttachmentMeta(context: android.content.Context, uri: Uri): PendingAttachment {
    val resolver = context.contentResolver
    val mime = resolver.getType(uri) ?: "application/octet-stream"
    var name = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    var size = 0L
    runCatching {
        resolver.query(uri, null, null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                val nameIdx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameIdx >= 0 && !c.isNull(nameIdx)) name = c.getString(nameIdx)
                val sizeIdx = c.getColumnIndex(OpenableColumns.SIZE)
                if (sizeIdx >= 0 && !c.isNull(sizeIdx)) size = c.getLong(sizeIdx)
            }
        }
    }
    return PendingAttachment(uri = uri, fileName = name, size = size, mime = mime)
}

/** Прочитать байты файла для загрузки. `null` — доступ отозван, ошибка чтения или файл больше [MAX_ATTACHMENT_BYTES]. */
private fun readOutgoing(context: android.content.Context, pending: PendingAttachment): OutgoingAttachment? {
    if (pending.size > MAX_ATTACHMENT_BYTES) return null
    val bytes = runCatching {
        context.contentResolver.openInputStream(pending.uri)?.use { it.readBytes() }
    }.getOrNull() ?: return null
    if (bytes.size > MAX_ATTACHMENT_BYTES) return null
    return OutgoingAttachment(bytes = bytes, fileName = pending.fileName, mime = pending.mime)
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

/** Сколько первых строк ленты считаются «верхом» — подгрузка стартует заранее, до упора. */
private const val OLDER_PREFETCH_ITEMS = 3

@Composable
private fun OlderHistoryStatus(
    loading: Boolean,
    failed: Boolean,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!loading && !failed) return
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.meerbot_loading_older), style = MaterialTheme.typography.labelMedium)
            } else {
                Text(stringResource(R.string.meerbot_older_failed), style = MaterialTheme.typography.labelMedium)
                TextButton(onClick = onRetry) { Text(stringResource(R.string.meerbot_retry)) }
            }
        }
    }
}
