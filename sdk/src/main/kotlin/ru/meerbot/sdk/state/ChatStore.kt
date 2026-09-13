package ru.meerbot.sdk.state

import androidx.annotation.StringRes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Состояние экрана чата. Контракт совпадает с iOS ChatStore и RN reducer.
 *
 * Отличие от iOS: `ready`/`retryable` живут здесь, а не на контроллере — в Compose один
 * StateFlow на экран дешевле двух подписок, а поля всё равно нужны только UI.
 */
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val role: String,               // "user" | "assistant" | "system"
    val author: String? = null,     // "ai" | "manager" | null
    val authorName: String? = null,
    val content: String,
    val streaming: Boolean = false,
    /** Сообщение не доставлено (обрыв сети при отправке) — UI показывает возможность повтора. */
    val failed: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    /**
     * id строки на сервере — ключ слияния при догоне ленты.
     *
     * Отдельно от `id`: тот обязан существовать с первого кадра, ещё до отправки
     * (оптимистичное сообщение пользователя). `null` — строка пока живёт только на устройстве.
     * Параметр последний в списке: позиционные вызовы у хостов не должны сломаться.
     */
    val serverId: Long? = null,
)

enum class ChatMode(val raw: String) {
    Ai("ai"),
    PendingEscalation("pending_escalation"),
    Human("human"),
    Closed("closed");

    companion object {
        fun from(raw: String?, fallback: ChatMode = Ai): ChatMode =
            entries.firstOrNull { it.raw == raw } ?: fallback
    }
}

/**
 * Ошибка для экрана: машинный код (для аналитики хоста) и ресурс текста.
 * Текст берётся ресурсом, а не строкой, чтобы следовать локали устройства.
 */
data class ChatError(
    val code: String,
    @StringRes val messageRes: Int,
)

data class ChatState(
    val messages: List<ChatMessage> = emptyList(),
    val mode: ChatMode = ChatMode.Ai,
    val operatorTyping: String? = null,
    val draft: String = "",
    val sending: Boolean = false,
    val connectionError: ChatError? = null,
    /** Приветствие канала из handshake — показывается вместо дефолтной заглушки. */
    val greeting: String? = null,
    /** Handshake выполнен — можно отправлять. */
    val ready: Boolean = false,
    /** Текст, который не удалось отправить: UI показывает «Повторить». */
    val retryable: String? = null,
    /** Наибольший серверный id в ленте — курсор догона (`GET /mobile/messages?since=`). */
    val lastServerMessageId: Long = 0L,
)

class ChatStore {

    private val _state = MutableStateFlow(ChatState())
    val state: StateFlow<ChatState> = _state.asStateFlow()

    val messages: List<ChatMessage> get() = _state.value.messages
    val mode: ChatMode get() = _state.value.mode
    val sending: Boolean get() = _state.value.sending

    fun setDraft(text: String) = _state.update { it.copy(draft = text) }

    fun clearDraft() = _state.update { it.copy(draft = "") }

    fun setMode(mode: ChatMode) = _state.update { it.copy(mode = mode) }

    fun setOperatorTyping(name: String?) = _state.update { it.copy(operatorTyping = name) }

    fun setError(error: ChatError?) = _state.update { it.copy(connectionError = error) }

    fun setSending(value: Boolean) = _state.update { it.copy(sending = value) }

    fun setGreeting(text: String?) = _state.update { it.copy(greeting = text) }

    fun setReady(value: Boolean) = _state.update { it.copy(ready = value) }

    fun setRetryable(text: String?) = _state.update { it.copy(retryable = text) }

    /**
     * Курсор ленты на момент появления локальной строки: id → `lastServerMessageId` тогда. Записи
     * нет — лента с сервером на тот момент ещё не сверялась, курсор неизвестен.
     *
     * Эхом строки может быть только серверная строка НОВЕЕ этого курсора: всё, что не новее,
     * сервер записал до отправки. Без этого вчерашнее «да» из стартовой истории, пришедшей
     * после отправки, забирало себе новое «да»: вчерашняя строка пропадала из ленты, обрыв
     * засчитывался доставкой по её id (без «Повторить»), а настоящее эхо вставало вторым «да».
     * Не в [ChatMessage]: новое поле публичного data-класса сломало бы его конструктор у хостов.
     * Потокобезопасна: в тестах слияние идёт не с главного потока.
     */
    private val echoFloors = ConcurrentHashMap<String, Long>()

    /**
     * Лента хотя бы раз сверена с сервером после последнего сброса: курсор ИЗВЕСТЕН, даже
     * если он 0 (тред пуст). Отличает «тред был пуст при отправке» — тогда любая серверная
     * строка новее отправки, и время сравнивать не нужно — от «история ещё не пришла».
     * Раньше оба случая давали порог 0 и сверку по времени: часы устройства, спешащие больше
     * допуска, делали эхо первого сообщения неузнаваемым — оно и ответ двоились, а обрыв
     * отправки оставлял «Повторить» на доставленном (второй платный ответ модели).
     *
     * Ставится ПОСЛЕ записи курсора: отправка, прочитавшая флаг раньше, получит «неизвестен» и
     * сверку по времени, а не порог 0 при ещё не записанной странице.
     */
    @Volatile
    private var cursorKnown = false

    /**
     * Строки пользователя, чей серверный id подтверждён по `clientMessageId` (кадр `meta` или
     * история), а не угадан по тексту. Для них не нужны ни пороги эха, ни сверка времени: сервер
     * сам сказал, какая строка — эта отправка. Не в [ChatMessage]: см. [echoFloors].
     */
    private val idConfirmed: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * Сервер знает `clientMessageId` (пришёл `meta` с ним или история с ключом у строк
     * пользователя). Тогда строку пользователя узнают ТОЛЬКО по id: чужая строка с тем же текстом
     * (второе «да», сообщение с другого устройства) своей больше не станет. Не сбрасывается —
     * сервер обратно не откатывается, а сброс вернул бы угадывание по тексту.
     */
    @Volatile
    private var clientIdsSupported = false

    internal fun markClientIdsSupported() {
        clientIdsSupported = true
    }

    internal fun isIdConfirmed(localId: String): Boolean = localId in idConfirmed

    /**
     * Сервер подтвердил приём отправки [localId] под номером [serverId] (`meta` с
     * `clientMessageId`). Курсор не двигается: строки между прежним курсором и этой принесёт
     * догон. Пометку «не отправлено» не трогает — её снимает слияние, когда на сообщение есть
     * ответ (см. [isSettled]). Серверная копия той же строки, уже влитая догоном, убирается.
     */
    internal fun confirmUserMessage(localId: String, serverId: Long) {
        var confirmed = false
        _state.update { current ->
            confirmed = current.messages.any { it.id == localId && it.role == "user" }
            if (!confirmed) return@update current
            current.copy(
                messages = current.messages
                    .filterNot { it.serverId == serverId && it.id != localId }
                    .map { if (it.id == localId) it.copy(serverId = serverId) else it },
            )
        }
        if (confirmed) {
            idConfirmed += localId
            echoFloors.remove(localId)
        }
    }

    /**
     * На сообщение [localId] уже есть реакция сервера: после него в ленте есть более новая
     * серверная строка (ответ, либо следующее сообщение, отменившее ответ на это), или диалог
     * ведёт менеджер — ответ придёт от человека, повтор ничего не ускорит.
     */
    internal fun isSettled(localId: String): Boolean {
        val current = _state.value
        val message = current.messages.firstOrNull { it.id == localId } ?: return false
        return isSettled(message, current.messages, current.mode)
    }

    private fun isSettled(message: ChatMessage, messages: List<ChatMessage>, mode: ChatMode): Boolean {
        if (mode == ChatMode.Human || mode == ChatMode.PendingEscalation) return true
        val serverId = message.serverId ?: return false
        return messages.any { (it.serverId ?: 0L) > serverId }
    }

    fun appendUserMessage(content: String): ChatMessage {
        val msg = ChatMessage(role = "user", content = content)
        _state.update { it.copy(messages = it.messages + msg) }
        rememberEchoFloor(msg.id)
        return msg
    }

    fun appendAssistantPlaceholder(): ChatMessage {
        val msg = ChatMessage(role = "assistant", author = "ai", content = "", streaming = true)
        _state.update { it.copy(messages = it.messages + msg) }
        rememberEchoFloor(msg.id)
        return msg
    }

    /**
     * Сообщение отправляется снова («Повторить»): его эхо обязано быть новее курсора на момент
     * повтора. Всё, что уже в ленте, записано раньше и эхом этой отправки не является.
     */
    internal fun markResent(id: String) = rememberEchoFloor(id)

    private fun rememberEchoFloor(id: String) {
        if (cursorKnown) echoFloors[id] = _state.value.lastServerMessageId else echoFloors.remove(id)
    }

    /**
     * Дописать кусок потока. Пробелы В НАЧАЛЕ ответа отбрасываются, пока текст пуст.
     *
     * Модель начинает ответ с перевода строки чаще, чем кажется (стабильно — на ответе про
     * передачу менеджеру). Сервер такой ответ сохраняет уже подрезанным, поэтому лишний
     * `\n` жил только на устройстве: пузырь начинался с пустой строки, а догон ленты не
     * узнавал в нём свою же строку и клал серверную копию рядом — сообщение двоилось.
     */
    fun updateAssistantContent(id: String, delta: String) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id != id) m
                else m.copy(content = if (m.content.isEmpty()) m.content + delta.trimStart() else m.content + delta)
            }
        )
    }

    /** Ответ дописан: хвостовые пробелы убираем — на сервере строка хранится без них. */
    fun finalizeAssistant(id: String) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id == id) m.copy(streaming = false, content = m.content.trimEnd()) else m
            }
        )
    }

    fun appendOperatorMessage(content: String, authorName: String?) {
        val msg = ChatMessage(
            role = "assistant",
            author = "manager",
            authorName = authorName,
            content = content,
        )
        _state.update { it.copy(messages = it.messages + msg) }
        rememberEchoFloor(msg.id)
    }

    /** Пометить сообщение недоставленным (обрыв сети) либо снять пометку при повторе. */
    fun setFailed(id: String, value: Boolean) = _state.update { current ->
        current.copy(
            messages = current.messages.map { m ->
                if (m.id == id) m.copy(failed = value) else m
            }
        )
    }

    /** Убрать пустой стриминговый плейсхолдер (ответ так и не начался). */
    fun dropEmptyPlaceholder(id: String) = _state.update { current ->
        current.copy(messages = current.messages.filterNot { it.id == id && it.content.isEmpty() })
    }

    /** Убрать сообщение из ленты (недописанный пузырь, когда серверная версия ответа уже в ней). */
    internal fun removeMessage(id: String) {
        _state.update { current ->
            current.copy(messages = current.messages.filterNot { it.id == id })
        }
        idConfirmed.remove(id)
    }

    val lastServerMessageId: Long get() = _state.value.lastServerMessageId

    /**
     * Заменить всю ленту.
     *
     * Стирает и неподтверждённые сообщения (отправляемое, недоставленное). SDK сам этот метод
     * не зовёт: история вливается через [mergeServerMessages], иначе ответ истории, пришедший
     * после отправки, убирал бы отправленное сообщение с экрана.
     */
    fun replaceAll(items: List<ChatMessage>) {
        _state.update {
            it.copy(messages = items, lastServerMessageId = bumpCursor(it.lastServerMessageId, items))
        }
        val kept = items.mapTo(HashSet()) { it.id }
        idConfirmed.retainAll(kept)
        cursorKnown = true
    }

    /**
     * Влить серверную страницу в ленту — и догон `since`, и полную историю (старт экрана,
     * сверка после обрыва и рестарта сервера). Идемпотентно по `serverId`; неподтверждённые
     * локальные сообщения не пропадают никогда. Зеркало iOS `ChatStore.mergeServerMessages`.
     *
     * Два прохода, и порядок между ними важен.
     *
     * Проход 1, от новых строк страницы к старым — узнать своё:
     *   1. `serverId` уже в ленте — пропускаем (страница пришла повторно, это норма догона);
     *   2. есть локальный двойник (тот же `role` и текст, ещё без серверного id, и строка
     *      могла быть записана после его появления — см. [canBeEcho]) — ПРОМОУТИМ его, а не
     *      добавляем второй: иначе своё же сообщение пользователь увидит дважды. Идём от
     *      новых к старым, чтобы эхо забрала самая новая строка с этим текстом. Стримящийся
     *      пузырь не промоутим: он ещё дописывается, и серверная строка с тем же текстом — не
     *      его окончательная версия. После прохода текст «Повторить» (если он был) берётся у
     *      последней ещё недоставленной строки, а без таких снимается: промоутнутое сообщение
     *      дошло, повтор отправил бы его второй раз.
     *
     * Проход 2, по порядку страницы — вставить остальное на своё место (см. [insertionIndex]),
     * а не в конец: стартовая история, пришедшая после отправки, старше отправленного
     * сообщения и должна встать над ним.
     *
     * Курсор двигается ВСЕГДА, даже если вся страница пропущена: иначе следующий догон
     * запросил бы те же строки и цикл никогда бы не сдвинулся.
     *
     * Сервер знает `clientMessageId` — см. перегрузку с `clientIds`.
     *
     * @return сколько сообщений реально появилось в ленте.
     */
    fun mergeServerMessages(items: List<ChatMessage>): Int = mergeServerMessages(items, emptyMap())

    /**
     * Слияние с `clientMessageId` строк пользователя: [clientIds] — серверный id → id клиента.
     *
     * Строка с `clientMessageId` промоутит ТОЛЬКО локальную строку пользователя с тем же id (без
     * учёта регистра) — без сверки текста, порога эха, часов и стриминга; пометка «не отправлено»
     * остаётся (её снимает нормализация ниже). Нет такой локальной строки — строка не наша и по
     * тексту не сверяется. Строка пользователя без id на сервере, который id знает, — тоже не
     * наша. Сверка по тексту остаётся для ответов ассистента и для старого сервера.
     *
     * Нормализация после слияния: подтверждённая по id строка, на которую уже есть реакция
     * сервера ([isSettled]), доставлена — пометка и «Повторить» снимаются. Без реакции пометка
     * остаётся: повтор с тем же id безопасен (сервер не сохранит сообщение дважды и догенерирует
     * ответ, только если его нет).
     */
    internal fun mergeServerMessages(items: List<ChatMessage>, clientIds: Map<Long, String>): Int {
        var added = 0
        var confirmedNow: List<String> = emptyList()
        _state.update { current ->
            val merged = current.messages.toMutableList()
            val recognized = HashSet<Int>()
            val confirmed = ArrayList<String>()
            val byIdOnly = clientIdsSupported
            for (index in items.indices.reversed()) {
                val item = items[index]
                if (item.serverId != null && merged.any { it.serverId == item.serverId }) {
                    recognized += index
                    continue
                }
                val clientId = item.serverId?.let { clientIds[it] }
                if (clientId != null) {
                    val localIdx = merged.indexOfFirst {
                        it.serverId == null && it.role == "user" && it.id.equals(clientId, ignoreCase = true)
                    }
                    if (localIdx >= 0) {
                        merged[localIdx] = merged[localIdx].copy(serverId = item.serverId)
                        confirmed += merged[localIdx].id
                        recognized += index
                    }
                    continue
                }
                if (byIdOnly && item.role == "user") continue
                val localIdx = indexOfLocalTwin(merged, item)
                if (localIdx >= 0) {
                    merged[localIdx] = merged[localIdx].copy(serverId = item.serverId, failed = false)
                    recognized += index
                }
            }

            // Счётчик — локальный: `update` может перезапустить лямбду при гонке записи.
            var inserted = 0
            for ((index, item) in items.withIndex()) {
                if (index in recognized) continue
                // Одна и та же строка дважды в странице.
                if (item.serverId != null && merged.any { it.serverId == item.serverId }) continue
                merged.add(insertionIndex(merged, item), item)
                inserted++
            }

            // Реакция сервера ищется по ленте ПОСЛЕ вставки: ответ обычно приходит той же страницей.
            for (i in merged.indices) {
                val m = merged[i]
                if (m.failed && (m.id in idConfirmed || m.id in confirmed) && isSettled(m, merged, current.mode)) {
                    merged[i] = m.copy(failed = false)
                }
            }
            // «Повторить» следует за последней ещё недоставленной строкой, а если таких не
            // осталось — снимается: повтор отправил бы доставленное второй раз. Сверка по тексту
            // здесь не годится — эхо более нового из двух недоставленных снимало бы кнопку и у
            // старого, который так и не дошёл. Паритет с iOS `mergeServerPage`.
            val retryable = current.retryable?.let {
                merged.lastOrNull { m -> m.failed && m.role == "user" }?.content
            }
            added = inserted
            confirmedNow = confirmed
            current.copy(
                messages = merged,
                retryable = retryable,
                lastServerMessageId = bumpCursor(current.lastServerMessageId, items),
            )
        }
        confirmedNow.forEach {
            idConfirmed += it
            echoFloors.remove(it)
        }
        cursorKnown = true
        return added
    }

    /**
     * Локальный двойник серверной строки: тот же `role` и текст, ещё без серверного id, не
     * стримится, и строка могла появиться на сервере после него ([canBeEcho]).
     *
     * Сравнение по ПОДРЕЗАННОМУ тексту: сервер хранит ответ без крайних пробелов, а в потоке
     * они приходят (первым чанком часто идёт перевод строки). Точное равенство роняло слияние
     * в дубль ровно на таких ответах.
     */
    private fun indexOfLocalTwin(messages: List<ChatMessage>, item: ChatMessage): Int =
        messages.indexOfLast {
            it.serverId == null && !it.streaming && it.role == item.role &&
                it.content.trim() == item.content.trim() && canBeEcho(it, item)
        }

    /**
     * Может ли серверная строка быть эхом локальной. Старая строка с тем же текстом эхом не
     * бывает никогда. Паритет с iOS.
     *
     * Курсор на момент появления известен (лента сверялась с сервером, в том числе пустой
     * страницей — тогда он 0) — эхо только новее него по серверному id, время не сравнивается.
     * Не известен (история ещё не пришла) — сравниваем время: серверный `createdAt` не раньше
     * локального минус [ECHO_CLOCK_TOLERANCE_MS] (часы устройства могут спешить; спешащие
     * больше допуска в этом окне эхо не узнают).
     */
    private fun canBeEcho(local: ChatMessage, item: ChatMessage): Boolean {
        val floor = echoFloors[local.id]
        return if (floor != null) {
            (item.serverId ?: return false) > floor
        } else {
            item.timestamp >= local.timestamp - ECHO_CLOCK_TOLERANCE_MS
        }
    }

    /**
     * Серверная строка, которой может быть подтверждена отправка [localId]: курсор на момент
     * отправки известен — новее него, иначе — любая. Для сверки после обрыва: ответ, записанный
     * до отправки, доставкой не считается.
     */
    internal fun isAfterSend(localId: String, serverId: Long): Boolean {
        // Подтверждено сервером по id — порог не нужен: это именно эта отправка.
        if (localId in idConfirmed) return true
        val floor = echoFloors[localId]
        return floor == null || serverId > floor
    }

    /**
     * Место новой серверной строки — сразу после последней строки ленты, которая раньше неё.
     *
     * Между серверными строками порядок задаёт `serverId` (он растёт на сервере). С
     * неподтверждёнными сравнивать можно только время: серверный `createdAt` против часов
     * устройства. Расхождение часов на секунды может переставить соседей — ответ менеджера,
     * пришедший в те же секунды, что и недоставленное сообщение, — но ничего не теряет и не
     * двоит. Главный случай (стартовая история старше отправки на минуты и дни) оно не задевает.
     */
    private fun insertionIndex(messages: List<ChatMessage>, item: ChatMessage): Int {
        val itemId = item.serverId
        return messages.indexOfLast { existing ->
            val existingId = existing.serverId
            if (existingId != null && itemId != null) existingId < itemId
            else existing.timestamp <= item.timestamp
        } + 1
    }

    /** Курсор только растёт: страница старее текущего значения не имеет права его откатить. */
    private fun bumpCursor(current: Long, items: List<ChatMessage>): Long =
        maxOf(current, items.mapNotNull { it.serverId }.maxOrNull() ?: 0L)

    fun resetForLogout() {
        cursorKnown = false
        _state.value = ChatState()
        echoFloors.clear()
        idConfirmed.clear()
    }

    /**
     * Сменился пользователь: лента, режим, черновик и курсор принадлежат прежнему и уходят.
     * Приветствие — настройка хоста, а не переписка, поэтому остаётся.
     */
    internal fun resetForIdentityChange() {
        cursorKnown = false
        _state.update { ChatState(greeting = it.greeting) }
        echoFloors.clear()
        idConfirmed.clear()
    }

    /**
     * Рукопожатие вернуло другое устройство (тот же человек, но отставная строка восстановлена
     * или заведена новая): серверные строки и курсор относятся к треду прежнего устройства и
     * уходят, догон загрузит историю заново с нуля. Без сброса `since=` по курсору прежнего треда
     * пропустил бы строки нового, что старше курсора, — id сообщений глобальные.
     *
     * Неподтверждённые строки (отправляемое, недоставленное) остаются: пропасть молча они не
     * вправе, а своё эхо узнают в новой истории. Пороги эха остаются — они нижние границы по
     * глобальным id и верны для любого треда.
     */
    internal fun resetForDeviceChange() {
        cursorKnown = false
        _state.update { current ->
            val kept = current.messages.filter { it.serverId == null }
            current.copy(
                messages = kept,
                lastServerMessageId = 0L,
                retryable = current.retryable?.let { kept.lastOrNull { m -> m.failed && m.role == "user" }?.content },
            )
        }
        idConfirmed.clear()
    }

    private companion object {
        /** Допуск на расхождение часов устройства и сервера при сверке эха по времени (как iOS). */
        const val ECHO_CLOCK_TOLERANCE_MS = 5 * 60 * 1000L
    }
}
