package ru.meerbot.sdk.network

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import ru.meerbot.sdk.state.ChatMode
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.random.Random

/** Ответ рукопожатия `/api/v1/mobile/register`. */
data class MobileSession(
    val deviceId: String,
    val jwt: String,
    val expiresIn: Int,
    val attestationRequired: Boolean,
    val identityStatus: IdentityStatus,
)

/** Страница истории: сервер отдаёт вместе с сообщениями и текущий режим диалога. */
data class HistoryPage(
    val messages: List<HistoryMessage>,
    val hasMore: Boolean,
    val mode: ChatMode,
) {
    /**
     * Сервер знает `clientMessageId`: хоть одна строка пользователя на странице несёт ключ (пусть
     * и `null` у старых строк). Страница без строк пользователя о поддержке ничего не говорит.
     * В теле класса — вне конструктора и `equals` публичного data-класса.
     */
    internal var clientMessageIdsSupported: Boolean = false
}

/** Сообщение из истории `/api/v1/mobile/messages`. */
data class HistoryMessage(
    val id: Long,
    val role: String,
    val content: String,
    /** `ai` | `manager` у ответов ассистентской роли, иначе `null`. Машинный дискриминатор:
     *  подпись (`authorName`) у менеджера может отсутствовать, а автор — нет. Сервер отдаёт
     *  поле с 2026-08-23; у старых сборок платформы его нет. */
    val authorKind: String?,
    val authorName: String?,
    val createdAtMs: Long,
) {
    /**
     * `clientMessageId` строки пользователя (в нижнем регистре), с которым её отправило
     * устройство; `null` — строка ассистента, строка старого клиента или старый сервер. По нему
     * SDK узнаёт своё отправленное сообщение без сверки текста. Поле в теле класса: новый параметр
     * конструктора публичного data-класса сломал бы приложения, собранные против 0.2.8.
     */
    var clientMessageId: String? = null
        internal set
}

/**
 * Клиент канала `mobile_app`: держит JWT, обновляет его по истечении и стримит ответы.
 *
 * Потокобезопасен: рукопожатие сериализовано мьютексом, поэтому параллельные отправки не
 * выписывают по своему JWT (сервер держит jti-allowlist, лишние токены — мусор).
 */
class ApiClient internal constructor(
    private val config: MeerBotConfiguration,
    private val visitorUuid: String,
    /**
     * Стабильный идентификатор установки. Уходит в поле `deviceToken` рукопожатия: сервер
     * ключует по нему `MobileDevice`, а тред диалога — по устройству. Поэтому значение
     * обязано пережить и перезапуск, и получение пуш-токена: подменить его — значит начать
     * пользователю новую переписку с чистого листа.
     *
     * Пуш-токен сюда НЕ кладётся: своей отправки пушей у платформы нет, ответ менеджера
     * уходит вебхуком на бэкенд интегратора (план поставки 1, этап E).
     */
    private val installationId: String,
    private val httpClient: OkHttpClient,
    /** Сигнал выхода, не подтверждённый сервером. Переживает процесс, если хранилище это умеет. */
    private val logoutFlag: LogoutFlagStore,
    /** Счётчик смен identity (`identitySeq`). Растёт вместе с флагом выхода, см. [IdentitySeqStore]. */
    private val identitySeq: IdentitySeqStore = InMemoryIdentitySeqStore(),
) {

    /**
     * Публичный конструктор ровно в форме 0.2.8 — `(config, visitorUuid, installationId,
     * httpClient = default)`. Пятый параметр в публичной сигнатуре убрал бы из байткода и
     * 4-аргументный конструктор, и синтетический с маской умолчаний: приложение, собранное
     * против 0.2.8, падало бы `NoSuchMethodError` на патч-обновлении. Хранилище флага выхода —
     * деталь `MeerBot`, снаружи его задавать незачем; здесь флаг живёт в памяти.
     */
    constructor(
        config: MeerBotConfiguration,
        visitorUuid: String,
        installationId: String,
        httpClient: OkHttpClient = defaultHttpClient(),
    ) : this(config, visitorUuid, installationId, httpClient, InMemoryLogoutFlagStore(), InMemoryIdentitySeqStore())

    private val tokenMutex = Mutex()

    /**
     * Защищает связку «поколение identity ↔ сохранённая сессия». Мьютекс рукопожатия здесь не
     * годится: `logout()`/`setIdentityToken()` не suspend и зовутся с главного потока.
     */
    private val sessionLock = Any()

    /**
     * Поколение identity: растёт при смене человека (выход, другой `sub`, первый токен).
     * Рукопожатие, начатое в прежнем поколении, своей сессии не сохраняет — иначе JWT,
     * выписанный на привязанное устройство прежнего пользователя, пережил бы выход и открыл
     * бы его ленту. Пишется и читается только под [sessionLock].
     */
    private var generation = 0

    /**
     * Ревизия токена того же человека ([refreshIdentityToken]). Отдельно от [generation]: ответ,
     * полученный со старым токеном ТОГО ЖЕ пользователя, чужим не является — его JWT отдаётся
     * ждущему запросу, но не кэшируется, и следующий запрос зарегистрируется уже со свежим
     * токеном. Повтора нет: он съедал бы попытку и делал лишний `/register` (паритет с iOS).
     */
    private var tokenRevision = 0

    /**
     * Выходы, записанные на диск ([persistLogoutIntent]), но ещё не применённые ([logout]).
     * Пока такой есть, рукопожатие флаг не снимает. Ревизии «до и после записи» здесь мало:
     * рукопожатие, начатое ПОСЛЕ записи, но до применения, несёт `logout` вместе с токеном
     * уходящего пользователя, и сервер привязывает устройство к нему обратно. Сними ответ флаг —
     * процесс, убитый до применения выхода, забыл бы его, и следующий человек открыл бы тред
     * прежнего. Пишется и читается только под [sessionLock].
     */
    private var unappliedLogouts = 0

    /** Клиент заменён повторным `configure` или `reset()` и общий флаг выхода не трогает. */
    private var retired = false

    @Volatile
    private var jwt: String? = null

    @Volatile
    private var jwtExpiresAtMs: Long = 0L

    @Volatile
    private var identityToken: String? = null

    /** `deviceId` последнего сохранённого рукопожатия. Пишется и читается только под [sessionLock]. */
    private var lastDeviceId: String? = null

    /**
     * Ревизия устройства: растёт, когда сохранённое рукопожатие вернуло ДРУГОЙ `deviceId`, чем
     * прежнее (сервер завёл новую строку устройства или восстановил отставную). Тред у нового
     * устройства другой, а id сообщений глобальные: догон `since=` по курсору прежнего треда
     * пропустил бы все строки нового, что старше курсора. Контроллер, увидев новую ревизию,
     * сбрасывает серверную часть ленты и курсор и грузит историю заново.
     */
    @Volatile
    internal var deviceRevision: Int = 0
        private set

    /**
     * Ожидание перед повтором после 503. Подменяется в тестах, чтобы проверять задержку, а не
     * спать; в работе — обычный `delay`, отменяемый вместе с запросом.
     */
    internal var unavailableRetryDelay: suspend (Long) -> Unit = { delay(it) }

    /** Диалог текущего устройства. Приходит из `meta`; в запросы НЕ уходит. */
    @Volatile
    var conversationId: Long? = null
        private set

    /**
     * Запомнить id диалога из пуша (`MeerBot.handlePush`). В запросы он по-прежнему не уходит
     * — тред резолвится по устройству из токена; значение нужно ХОСТУ, чтобы не показывать
     * баннер о сообщении, открытом сейчас на экране. Паритет с iOS `setConversationId`.
     */
    internal fun rememberConversationId(id: Long) {
        conversationId = id
    }

    /** id последнего известного сообщения — точка догона после обрыва. */
    @Volatile
    var lastMessageId: Long? = null
        private set

    /** Статус identity с последнего рукопожатия. */
    @Volatile
    var identityStatus: IdentityStatus = IdentityStatus.NotProvided
        private set

    /**
     * Подписанный бэкендом интегратора токен идентичности. Следующее рукопожатие уйдёт с ним;
     * текущая сессия сбрасывается, иначе identity подхватилась бы только через 15 минут.
     *
     * `null` здесь — только «токена нет», устройство от пользователя НЕ отвязывается: сервер
     * держит связь, пока не придёт явный выход (`MeerBot.identify(null)`). Диалог, курсор и
     * статус identity сбрасываются, флаг выхода не ставится.
     *
     * Смена человека определяется по `sub` против токена, заданного в ЭТОМ экземпляре, тем же
     * правилом, что у `MeerBot.identify` ([IdentitySubject.change]):
     * - свежий токен того же `sub` (оба читаются и совпали) — токен уходит в следующее
     *   рукопожатие, диалог и курсор остаются;
     * - любой другой токен — другой `sub`, `sub`, который не читается, или первый токен
     *   экземпляра (кто был связан с устройством до него, экземпляр не знает) — выход плюс вход:
     *   ставится флаг выхода, и следующее рукопожатие несёт `logout: true` с новым токеном, даже
     *   если токен нового сервер не примет. Диалог, курсор и статус сбрасываются. Лишней
     *   отставки флаг не даёт: тот же `sub` с подписанным токеном и анонимную строку сервер
     *   оставляет как есть. Паритет с iOS `APIClient.setIdentityToken`.
     *
     * Клиент, собранный публичным конструктором, держит флаг выхода в памяти процесса: убитый
     * до рукопожатия процесс его забудет. Флаг на диске и сравнение с пользователем прошлого
     * запуска — у `MeerBot.identify`.
     */
    fun setIdentityToken(token: String?) {
        synchronized(sessionLock) {
            if (token == null) {
                identityToken = null
                generation++
                invalidateToken()
                conversationId = null
                lastMessageId = null
                identityStatus = IdentityStatus.NotProvided
                return
            }
            // Решение и применение — под одним замком (он реентрантный): два параллельных вызова
            // иначе сравнили бы свои токены с одним и тем же прежним.
            val subject = IdentitySubject.of(token)
            val change = IdentitySubject.change(
                previousHash = identityToken?.let { IdentitySubject.hash(installationId, IdentitySubject.key(it)) },
                newHash = IdentitySubject.hash(installationId, subject ?: token),
                newSubjectReadable = subject != null,
            )
            if (change == IdentityChange.Refresh) refreshIdentityToken(token) else switchIdentity(token)
        }
    }

    /**
     * Свежий токен ТОГО ЖЕ пользователя (тот же `sub`). Сессия переоткрывается с ним, но
     * рукопожатие в полёте не отменяется: его ответ принадлежит тому же человеку.
     */
    internal fun refreshIdentityToken(token: String) {
        synchronized(sessionLock) {
            identityToken = token
            tokenRevision++
            invalidateToken()
        }
    }

    /**
     * Выход пользователя. Следующее рукопожатие несёт `logout: true`, и сервер (с 0.2.9 SDK)
     * уводит привязанное устройство в отставку: прежний тред остаётся прежнему пользователю,
     * новый начинается пустым.
     *
     * Флаг снимается только ответом, в котором сервер сообщил `unlinked`: старый сервер поле
     * `logout` игнорирует, и сигнал уходит снова на каждом рукопожатии, пока сервер не обновят.
     * Для устройства без связи повтор ничего не меняет.
     *
     * Применяет один выход, записанный [persistLogoutIntent]: с этого момента рукопожатие несёт
     * `logout` уже без токена уходящего пользователя, и его подтверждение флаг снимает.
     */
    internal fun logout() {
        synchronized(sessionLock) {
            beginNewIdentity(token = null)
            if (unappliedLogouts > 0) unappliedLogouts--
        }
    }

    /**
     * Клиент заменён (повторный `configure`, `reset()`). Ответ, пришедший ему после этого, флаг
     * выхода не снимает: хранилище флага общее с новым клиентом, а сессия и счётчики — нет.
     * Иначе ответ старому стёр бы флаг на диске, пока новый держит его только в памяти, и
     * убитый процесс забыл бы выход. Поколение растёт — рукопожатие в полёте своей сессии не
     * сохранит.
     */
    internal fun retire() {
        synchronized(sessionLock) {
            retired = true
            generation++
            invalidateToken()
        }
    }

    /**
     * Вошёл ДРУГОЙ пользователь, а выхода прежнего не было. Это тот же выход плюс новый токен
     * одним рукопожатием: без `logout` сервер, получив устаревший (или отклонённый) токен
     * нового человека, оставил бы устройство за прежним — и новый увидел бы чужой тред.
     * Сервер сам решает, привязать ли устройство к новому `sub`.
     */
    internal fun switchIdentity(token: String) {
        beginNewIdentity(token = token)
    }

    /**
     * Записать выход на диск СИНХРОННО (`commit()`), на потоке вызывающего `identify(null)`, до
     * постановки вызова в очередь главного потока. Запись `apply()` из [beginNewIdentity]
     * ложится на диск позже, и процесс, убитый в этом окне, забыл бы выход: следующий человек
     * открыл бы тред прежнего. Запись редкая — раз на выход. Сбой хранилища пишется в лог
     * (`logout_flag_write_failed`), флаг остаётся в памяти процесса.
     *
     * Каждый вызов обязан завершиться [logout] (так делает `MeerBot`): до него рукопожатие флаг
     * не снимает (см. [unappliedLogouts]). Счётчик растёт ДО записи — рукопожатие, прочитавшее
     * флаг посреди `commit()`, тоже его не снимет.
     */
    internal fun persistLogoutIntent() {
        synchronized(sessionLock) { unappliedLogouts++ }
        // Счётчик identity — на диск РАНЬШЕ флага. Убитый между записями процесс со счётчиком без
        // флага ничего не теряет (лишний шаг счётчика безвреден), а флаг со старым счётчиком
        // ушёл бы выходом, который сервер вправе счесть устаревшим.
        identitySeq.increment(durable = true)
        logoutFlag.persistPending()
    }

    /**
     * Флаг, поколение и клиентское состояние прежнего человека меняются под одним замком: иначе
     * рукопожатие, закончившееся между записью флага и сменой поколения, сняло бы только что
     * поставленный флаг (его запрос ушёл без `logout`). Запись флага — `apply()` на
     * in-memory prefs, диск пишется позже и замок не держит. Для выхода это не окно потери: он
     * уже на диске ([persistLogoutIntent]), и до этого места снять его рукопожатие не могло.
     */
    private fun beginNewIdentity(token: String?) {
        synchronized(sessionLock) {
            // Счётчик — в той же критической секции, что флаг: рукопожатие, прочитавшее новый
            // флаг, всегда несёт и новый счётчик. Повторный шаг после [persistLogoutIntent]
            // безвреден — сервер сравнивает только «меньше».
            identitySeq.increment()
            logoutFlag.pending = true
            identityToken = token
            generation++
            invalidateToken()
            conversationId = null
            lastMessageId = null
            identityStatus = IdentityStatus.NotProvided
        }
    }

    // ─── Рукопожатие ──────────────────────────────────────────────────────────────────────

    suspend fun openSession(): MobileSession = tokenMutex.withLock { openSessionLocked() }

    private suspend fun openSessionLocked(): MobileSession {
        // Поколение сменилось посреди запроса — ответ принадлежит прежней identity и
        // отбрасывается, запрос повторяется с новой. Повторов конечное число (паритет с iOS):
        // хост, дёргающий identify() в цикле, не должен превратить рукопожатие в бесконечное.
        // Свежий токен того же человека повтора не требует (см. [tokenRevision]).
        // Повтор после 503 — отдельный и единственный: попытку смены identity он не съедает.
        var attempt = 0
        var unavailableRetried = false
        while (attempt < MAX_REGISTER_ATTEMPTS) {
            val startedIn: Int
            val startedRevision: Int
            val token: String?
            val logoutSent: Boolean
            val seqSent: Long
            synchronized(sessionLock) {
                startedIn = generation
                startedRevision = tokenRevision
                token = identityToken
                logoutSent = logoutFlag.pending
                seqSent = identitySeq.value
            }

            val body = JSONObject()
                .put("key", config.apiKey)
                .put("deviceToken", installationId)
                .put("platform", "android")
                .put("visitorUuid", visitorUuid)
                .put("sdkVersion", config.sdkVersion)
            token?.let { body.put("identityToken", it) }
            if (logoutSent) body.put("logout", true)
            // Всегда, в том числе 0: сервер упорядочивает выходы и входы по счётчику, а не по
            // часам бэкенда интегратора. Старый сервер поле игнорирует.
            body.put("identitySeq", seqSent)

            val request = newRequest("/api/v1/mobile/register")
                .post(body.toString().toRequestBody(JSON))
                .build()

            val raw = execute(request)
            // Поколение сверяется ДО разбора: ответ прежней identity — чужой, будь он хоть
            // битым, хоть отказом. Бросить его ошибку значило бы показать новому человеку
            // провал запроса, которого он не делал, вместо повтора с его токеном.
            if (synchronized(sessionLock) { generation != startedIn }) {
                attempt++
                continue
            }

            // 503 (`registration_conflict` параллельных регистраций, рестарт) — временный отказ:
            // один повтор после `Retry-After`, дальше ошибка уходит вызывающему.
            if (raw.code == HTTP_UNAVAILABLE && !unavailableRetried) {
                unavailableRetried = true
                unavailableRetryDelay(unavailableDelayMs(raw.retryAfter))
                continue
            }

            val session = parseSession(raw, logoutSent, seqSent, startedIn, startedRevision)
            if (session == null) {
                attempt++
                continue
            }
            return session
        }
        // Отмена, а не сетевая ошибка: рукопожатие перебили смены пользователя. Контроллер
        // считает её безобидной, только если сменилась и его эпоха.
        throw MeerBotError.Cancelled
    }

    /**
     * Разобрать ответ рукопожатия и сохранить сессию. `null` — сессия не сохранена: пока
     * разбирали, поколение сменилось, либо сервер сообщил счётчик identity больше отправленного.
     */
    private fun parseSession(
        raw: RawResponse,
        logoutSent: Boolean,
        seqSent: Long,
        startedIn: Int,
        startedRevision: Int,
    ): MobileSession? {
        val json = parseJson(raw)
        val jwtValue = json.optStringOrNull("jwt") ?: throw MeerBotError.InvalidResponse
        val deviceId = json.optStringOrNull("deviceId") ?: throw MeerBotError.InvalidResponse
        val expiresIn = json.optInt("expiresIn", 0)
        if (expiresIn <= 0) throw MeerBotError.InvalidResponse

        val identity = json.optJSONObject("identity")
        val status = IdentityStatus.from(identity?.optStringOrNull("status"))
        // Выход подтверждён, только если `unlinked` — булево значение (`false` — отвязывать
        // было нечего, но выход понят). Нет поля, `null` или строка — сервер старый либо ответ
        // кривой, и сигнал уйдёт снова. Паритет с iOS (`identity["unlinked"] is Bool`).
        val unlinkedConfirmed = identity != null && !identity.isNull("unlinked") &&
            identity.opt("unlinked") is Boolean
        val serverSeq = (identity?.takeUnless { it.isNull("seq") }?.opt("seq") as? Number)?.toLong()

        val committed = synchronized(sessionLock) {
            if (generation != startedIn) return@synchronized false
            // Сервер хранит счётчик больше отправленного: локальный потерян (сброс данных
            // приложения без `reset()`, битое хранилище). Такой запрос сервер счёл устаревшим и
            // identity из него не применил — ни токен, ни выход. Сессия не сохраняется, флаг не
            // снимается; счётчик догоняет серверный, и повтор уходит с актуальным значением.
            if (serverSeq != null && serverSeq > seqSent) {
                identitySeq.raiseTo(serverSeq)
                return@synchronized false
            }
            // Другое устройство, чем у прошлого рукопожатия (восстановление отставной строки,
            // новая строка после выхода): диалог и курсор прежнего к нему не относятся.
            val previousDevice = lastDeviceId
            if (previousDevice != null && previousDevice != deviceId) {
                deviceRevision++
                conversationId = null
                lastMessageId = null
            }
            lastDeviceId = deviceId
            // Свежий токен того же человека пришёл в полёте: JWT отдаём ждущему запросу (связь
            // та же), но не кэшируем — следующий запрос зарегистрируется со свежим токеном.
            // Статус тоже не публикуем: он описывает прежний токен, а хост уже передал новый
            // (паритет с iOS).
            if (tokenRevision == startedRevision) {
                jwt = jwtValue
                jwtExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
                identityStatus = status
            }
            if (logoutSent && unlinkedConfirmed && unappliedLogouts == 0 && !retired) logoutFlag.pending = false
            true
        }
        if (!committed) return null

        return MobileSession(
            deviceId = deviceId,
            jwt = jwtValue,
            expiresIn = expiresIn,
            attestationRequired = json.optBoolean("attestationRequired", false),
            identityStatus = status,
        )
    }

    /**
     * Действующий JWT: переиспользуем, пока до истечения больше минуты, иначе — новое
     * рукопожатие. Параллельные вызовы ждут на мьютексе и получают уже обновлённый токен.
     */
    suspend fun validToken(): String = tokenMutex.withLock {
        val current = jwt
        if (current != null && jwtExpiresAtMs - System.currentTimeMillis() > TOKEN_MIN_LIFETIME_MS) {
            return@withLock current
        }
        openSessionLocked().jwt
    }

    /** Пометить текущий токен недействительным (сервер ответил 401 `jwt_*`). */
    fun invalidateToken() {
        jwt = null
        jwtExpiresAtMs = 0L
    }

    // ─── История (догон после обрыва) ─────────────────────────────────────────────────────

    /**
     * История диалога. Диалог сервер резолвит по устройству из токена — передавать его id
     * клиенту нечем и незачем.
     */
    suspend fun history(since: Long? = null, limit: Int = 50): HistoryPage {
        val startedIn = currentGeneration()
        val url = (config.baseUrl.trimEnd('/') + "/api/v1/mobile/messages").toHttpUrl()
            .newBuilder()
            .addQueryParameter("limit", limit.toString())
            .apply { if (since != null) addQueryParameter("since", since.toString()) }
            .build()

        val json = executeAuthorizedJson { token ->
            Request.Builder()
                .url(url)
                .get()
                .applyCommonHeaders()
                .header("Authorization", "Bearer $token")
                .build()
        }

        val raw = json.optJSONArray("messages") ?: throw MeerBotError.InvalidResponse
        val messages = ArrayList<HistoryMessage>(raw.length())
        var clientIdsSupported = false
        for (i in 0 until raw.length()) {
            val item = raw.optJSONObject(i) ?: continue
            val id = item.optLongOrNull("id") ?: continue
            val role = item.optStringOrNull("role") ?: continue
            messages += HistoryMessage(
                id = id,
                role = role,
                content = item.optString("content"),
                authorKind = item.optStringOrNull("authorKind"),
                authorName = item.optStringOrNull("authorName"),
                createdAtMs = parseTimestamp(item.optStringOrNull("createdAt")),
            ).apply {
                // Ключ у строки пользователя (даже `null`) — сервер знает `clientMessageId`.
                if (role == "user" && item.has("clientMessageId")) {
                    clientIdsSupported = true
                    clientMessageId = item.optStringOrNull("clientMessageId")?.lowercase()
                }
            }
        }
        // Страница, запрошенная до смены человека, курсор нового не двигает.
        messages.lastOrNull()?.let { last ->
            synchronized(sessionLock) { if (generation == startedIn) lastMessageId = last.id }
        }
        // Режим приходит той же страницей: только так клиент узнаёт, что диалог закрыт или
        // уже ведёт менеджер, — рукопожатие канала режима не отдаёт.
        return HistoryPage(
            messages = messages,
            hasMore = json.optBoolean("hasMore", false),
            mode = ChatMode.from(json.optStringOrNull("mode")),
        ).apply { clientMessageIdsSupported = clientIdsSupported }
    }

    // ─── Стрим ответа ─────────────────────────────────────────────────────────────────────

    /**
     * Отправить сообщение и получить поток событий.
     *
     * События эмитятся по мере поступления. При обрыве поток выбрасывает
     * [MeerBotError.Network] — уже доставленные события остаются доставленными, вызывающая
     * сторона решает, догонять ли историю. Истёкший JWT (401 `jwt_*`) обновляется прозрачно,
     * запрос повторяется РОВНО один раз; 403 `channel_mismatch` не повторяется никогда —
     * перепутан ключ, и новый токен будет ровно таким же.
     */
    fun sendMessage(text: String): Flow<ChatStreamEvent> = sendMessage(text, clientMessageId = null)

    /**
     * Отправка с `clientMessageId` — идемпотентная: сервер не сохранит сообщение с этим id
     * дважды и не сгенерирует второй ответ, а на повтор отдаст уже готовый (`meta.replayed`).
     * Id уходит и в повтор после переподключения. `null` — прежний, неидемпотентный путь.
     */
    internal fun sendMessage(text: String, clientMessageId: String?): Flow<ChatStreamEvent> = flow {
        runStream(
            text = text,
            clientMessageId = clientMessageId,
            allowReauthorize = true,
            allowUnavailableRetry = true,
            collector = this,
            startedIn = currentGeneration(),
        )
    }.flowOn(Dispatchers.IO)

    private fun currentGeneration(): Int = synchronized(sessionLock) { generation }

    /** Почему запрос потока нужно повторить, не отдав вызывающему ни одного кадра. */
    private sealed class StreamRetry {
        /** 401 `jwt_*`: сессия переоткрывается, запрос повторяется один раз. */
        object Reauthorize : StreamRetry()

        /** 503: один повтор после паузы. */
        class Unavailable(val delayMs: Long) : StreamRetry()
    }

    /** @param startedIn поколение identity на момент отправки — см. [emit]. */
    private suspend fun runStream(
        text: String,
        clientMessageId: String?,
        allowReauthorize: Boolean,
        allowUnavailableRetry: Boolean,
        collector: FlowCollector<ChatStreamEvent>,
        startedIn: Int,
    ) {
        val body = JSONObject().put("message", text)
        clientMessageId?.let { body.put("clientMessageId", it) }

        val request = newRequest("/api/v1/mobile/chat/stream")
            .post(body.toString().toRequestBody(JSON))
            .header("Accept", "text/event-stream")
            .header("Authorization", "Bearer ${validToken()}")
            .build()

        // Читающий таймаут заметно больше 15-секундного heartbeat: молчание дольше этого —
        // мёртвое соединение, а не пауза в генерации.
        val call = httpClient.newBuilder()
            .readTimeout(STREAM_READ_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
            .newCall(request)

        // Повтор решается внутри, а выполняется снаружи: ответ закрыт, сторож вызова снят.
        val retry: StreamRetry? = cancelCallOnCancellation(call) {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw MeerBotError.Network(e.message ?: "io")
            }

            response.use {
                if (!it.isSuccessful) {
                    val retryAfter = it.header("Retry-After")
                    val error = decodeError(it.code, it.body?.string())
                    if (allowReauthorize && error.isExpiredToken) return@use StreamRetry.Reauthorize
                    // До первого байта потока: ни одного кадра вызывающий ещё не получил.
                    if (allowUnavailableRetry && it.code == HTTP_UNAVAILABLE) {
                        return@use StreamRetry.Unavailable(unavailableDelayMs(retryAfter))
                    }
                    throw error
                }

                val source = it.body?.source() ?: throw MeerBotError.InvalidResponse
                try {
                    SseReader(source).read { raw ->
                        currentCoroutineContext().ensureActive()
                        emit(raw, collector, startedIn)
                    }
                } catch (e: IOException) {
                    currentCoroutineContext().ensureActive()
                    throw MeerBotError.Network(e.message ?: "stream_broken")
                }
                null
            }
        }

        when (retry) {
            null -> Unit
            StreamRetry.Reauthorize -> {
                invalidateToken()
                runStream(text, clientMessageId, allowReauthorize = false, allowUnavailableRetry, collector, startedIn)
            }
            is StreamRetry.Unavailable -> {
                unavailableRetryDelay(retry.delayMs)
                runStream(text, clientMessageId, allowReauthorize, allowUnavailableRetry = false, collector, startedIn)
            }
        }
    }

    /**
     * Отмена корутины рвёт сокет [call]: `execute()` и `readUtf8Line()` блокирующие и сами её не
     * заметят. Раньше здесь стоял `Job.invokeOnCompletion { call.cancel() }` — он срабатывает на
     * ЗАВЕРШЕНИИ задачи, а задача, висящая в блокирующем чтении, не завершится, пока сервер не
     * пришлёт байт: закрытый экран держал запрос (и генерацию ответа) до следующего кадра.
     * Сторож — дочерняя корутина: отмена родителя отменяет её сразу, и она закрывает вызов.
     */
    private suspend fun <T> cancelCallOnCancellation(call: Call, block: suspend () -> T): T = coroutineScope {
        val finished = AtomicBoolean(false)
        val watcher = launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                awaitCancellation()
            } finally {
                if (!finished.get()) call.cancel()
            }
        }
        try {
            block()
        } finally {
            finished.set(true)
            watcher.cancel()
        }
    }

    /**
     * Кадр потока. Диалог и курсор пишутся, только пока identity та же, что при отправке:
     * поздний `meta` потока прежнего человека иначе вернул бы его `conversationId` новому
     * (хост сверял бы с ним пуши). Сам кадр отдаётся всегда — ленту стережёт эпоха контроллера.
     */
    private suspend fun emit(raw: SseEvent, collector: FlowCollector<ChatStreamEvent>, startedIn: Int) {
        val event = ChatStreamEvent.from(raw) ?: return
        synchronized(sessionLock) {
            if (generation == startedIn) {
                if (event is ChatStreamEvent.Meta && event.conversationId > 0) {
                    conversationId = event.conversationId
                }
                if (event is ChatStreamEvent.Manager && event.message.messageId > 0) {
                    lastMessageId = event.message.messageId
                }
            }
        }
        collector.emit(event)
    }

    // ─── Транспорт ────────────────────────────────────────────────────────────────────────

    private fun newRequest(path: String): Request.Builder =
        Request.Builder()
            .url(config.baseUrl.trimEnd('/') + path)
            .applyCommonHeaders()

    // `Origin` не отправляется сознательно: у нативного приложения его нет, а сервер
    // мобильного канала по нему ничего не проверяет (тира лимитов по origin у канала тоже нет).
    private fun Request.Builder.applyCommonHeaders(): Request.Builder =
        header("X-SDK-Version", config.sdkVersion)

    /** Ответ, прочитанный целиком, но ещё не разобранный. */
    private class RawResponse(val code: Int, val successful: Boolean, val text: String?, val retryAfter: String?)

    /** Выполнить запрос и прочитать тело. Разбор — отдельно: рукопожатию нужно сперва сверить поколение. */
    private suspend fun execute(request: Request): RawResponse = withContext(Dispatchers.IO) {
        val response = try {
            httpClient.newCall(request).execute()
        } catch (e: IOException) {
            throw MeerBotError.Network(e.message ?: "io")
        }
        response.use {
            val text = try {
                it.body?.string()
            } catch (e: IOException) {
                throw MeerBotError.Network(e.message ?: "io")
            }
            RawResponse(it.code, it.isSuccessful, text, it.header("Retry-After"))
        }
    }

    /** Запрос без Authorization. */
    private suspend fun executeJson(request: Request): JSONObject = parseJson(execute(request))

    /** Запрос с Authorization: 401 по протухшему JWT обновляет сессию и повторяется один раз. */
    private suspend fun executeAuthorizedJson(build: (String) -> Request): JSONObject {
        return try {
            executeJson(build(validToken()))
        } catch (e: MeerBotError) {
            if (!e.isExpiredToken) throw e
            invalidateToken()
            executeJson(build(validToken()))
        }
    }

    private fun parseJson(raw: RawResponse): JSONObject {
        if (!raw.successful) throw decodeError(raw.code, raw.text)
        if (raw.text.isNullOrEmpty()) throw MeerBotError.InvalidResponse
        return runCatching { JSONObject(raw.text) }.getOrElse { throw MeerBotError.InvalidResponse }
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private const val TOKEN_MIN_LIFETIME_MS = 60_000L
        /** Первая попытка рукопожатия и один повтор после смены identity в полёте (как iOS). */
        private const val MAX_REGISTER_ATTEMPTS = 2
        private const val STREAM_READ_TIMEOUT_S = 60L
        private const val HTTP_UNAVAILABLE = 503
        /** Пауза, если `Retry-After` нет или он не в секундах (HTTP-дата). */
        private const val UNAVAILABLE_DEFAULT_DELAY_MS = 1_000L
        /** Потолок паузы: дольше держать отправку (и мьютекс рукопожатия) нельзя — паритет с iOS. */
        private const val UNAVAILABLE_MAX_DELAY_MS = 5_000L
        /** Разброс: параллельные клиенты после общего 503 не должны прийти разом. */
        private const val UNAVAILABLE_MAX_JITTER_MS = 250L

        /** Пауза перед повтором после 503: `Retry-After` в секундах, не больше потолка, плюс разброс. */
        internal fun unavailableDelayMs(retryAfter: String?): Long {
            val seconds = retryAfter?.trim()?.toLongOrNull()
            val base = if (seconds == null) UNAVAILABLE_DEFAULT_DELAY_MS
            else (seconds.coerceAtLeast(0L) * 1000L).coerceAtMost(UNAVAILABLE_MAX_DELAY_MS)
            return base.coerceAtMost(UNAVAILABLE_MAX_DELAY_MS) + Random.nextLong(0L, UNAVAILABLE_MAX_JITTER_MS + 1)
        }

        fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        /**
         * Отказы канала приходят в форме `{error:{code,message}}` — без поля `type`,
         * в отличие от веб-виджета. Читаем только `code`: он единственный машинный.
         */
        fun decodeError(status: Int, body: String?): MeerBotError.Http {
            val error = body
                ?.let { runCatching { JSONObject(it) }.getOrNull() }
                ?.optJSONObject("error")
            return MeerBotError.Http(
                status = status,
                errorCode = error?.optStringOrNull("code") ?: "http_$status",
                serverMessage = error?.optStringOrNull("message") ?: "HTTP $status",
            )
        }

        private val ISO_FORMATS = listOf(
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )

        /** `createdAt` сервера — ISO-8601. Не разобрали — берём «сейчас», порядок ленты важнее. */
        fun parseTimestamp(raw: String?): Long {
            if (raw.isNullOrEmpty()) return System.currentTimeMillis()
            for (pattern in ISO_FORMATS) {
                val parsed = runCatching {
                    SimpleDateFormat(pattern, Locale.US)
                        .apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(raw)
                }.getOrNull()
                if (parsed != null) return parsed.time
            }
            return System.currentTimeMillis()
        }

        internal fun JSONObject.optStringOrNull(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        internal fun JSONObject.optLongOrNull(key: String): Long? =
            if (isNull(key)) null else optLong(key, -1L).takeIf { it >= 0L }
    }
}
