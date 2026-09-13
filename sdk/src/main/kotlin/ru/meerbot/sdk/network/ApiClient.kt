package ru.meerbot.sdk.network

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
)

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
)

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
    ) : this(config, visitorUuid, installationId, httpClient, InMemoryLogoutFlagStore())

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
     * Ревизия записанного выхода ([persistLogoutIntent]). Рукопожатие снимает флаг, только если
     * за время запроса выход не записали снова: иначе ответ на прежний выход стёр бы новый,
     * записанный на диск до того, как главный поток его применил.
     */
    private var logoutIntent = 0

    @Volatile
    private var jwt: String? = null

    @Volatile
    private var jwtExpiresAtMs: Long = 0L

    @Volatile
    private var identityToken: String? = null

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
     */
    internal fun logout() {
        beginNewIdentity(token = null)
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
     */
    internal fun persistLogoutIntent() {
        synchronized(sessionLock) { logoutIntent++ }
        logoutFlag.persistPending()
    }

    /**
     * Флаг, поколение и клиентское состояние прежнего человека меняются под одним замком: иначе
     * рукопожатие, закончившееся между записью флага и сменой поколения, сняло бы только что
     * поставленный флаг (его запрос ушёл без `logout`). Запись флага — `apply()` на
     * in-memory prefs, диск пишется позже и замок не держит.
     */
    private fun beginNewIdentity(token: String?) {
        synchronized(sessionLock) {
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
        repeat(MAX_REGISTER_ATTEMPTS) {
            val startedIn: Int
            val startedRevision: Int
            val startedIntent: Int
            val token: String?
            val logoutSent: Boolean
            synchronized(sessionLock) {
                startedIn = generation
                startedRevision = tokenRevision
                startedIntent = logoutIntent
                token = identityToken
                logoutSent = logoutFlag.pending
            }

            val body = JSONObject()
                .put("key", config.apiKey)
                .put("deviceToken", installationId)
                .put("platform", "android")
                .put("visitorUuid", visitorUuid)
                .put("sdkVersion", config.sdkVersion)
            token?.let { body.put("identityToken", it) }
            if (logoutSent) body.put("logout", true)

            val request = newRequest("/api/v1/mobile/register")
                .post(body.toString().toRequestBody(JSON))
                .build()

            val raw = execute(request)
            // Поколение сверяется ДО разбора: ответ прежней identity — чужой, будь он хоть
            // битым, хоть отказом. Бросить его ошибку значило бы показать новому человеку
            // провал запроса, которого он не делал, вместо повтора с его токеном.
            if (synchronized(sessionLock) { generation != startedIn }) return@repeat

            val session = parseSession(raw, logoutSent, startedIn, startedRevision, startedIntent)
                ?: return@repeat
            return session
        }
        // Отмена, а не сетевая ошибка: рукопожатие перебили смены пользователя. Контроллер
        // считает её безобидной, только если сменилась и его эпоха.
        throw MeerBotError.Cancelled
    }

    /**
     * Разобрать ответ рукопожатия и сохранить сессию. `null` — пока разбирали, поколение
     * сменилось, и сессия не сохранена.
     */
    private fun parseSession(
        raw: RawResponse,
        logoutSent: Boolean,
        startedIn: Int,
        startedRevision: Int,
        startedIntent: Int,
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

        val committed = synchronized(sessionLock) {
            if (generation != startedIn) return@synchronized false
            // Свежий токен того же человека пришёл в полёте: JWT отдаём ждущему запросу (связь
            // та же), но не кэшируем — следующий запрос зарегистрируется со свежим токеном.
            // Статус тоже не публикуем: он описывает прежний токен, а хост уже передал новый
            // (паритет с iOS).
            if (tokenRevision == startedRevision) {
                jwt = jwtValue
                jwtExpiresAtMs = System.currentTimeMillis() + expiresIn * 1000L
                identityStatus = status
            }
            if (logoutSent && unlinkedConfirmed && logoutIntent == startedIntent) logoutFlag.pending = false
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
            )
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
        )
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
    fun sendMessage(text: String): Flow<ChatStreamEvent> = flow {
        runStream(text, allowRetry = true, collector = this, startedIn = currentGeneration())
    }.flowOn(Dispatchers.IO)

    private fun currentGeneration(): Int = synchronized(sessionLock) { generation }

    /** @param startedIn поколение identity на момент отправки — см. [emit]. */
    private suspend fun runStream(
        text: String,
        allowRetry: Boolean,
        collector: FlowCollector<ChatStreamEvent>,
        startedIn: Int,
    ) {
        val body = JSONObject().put("message", text)

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

        // Отмена корутины должна рвать сокет: readUtf8Line() блокирующий и сам её не заметит.
        val cancelHandle = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }

        try {
            val response = try {
                call.execute()
            } catch (e: IOException) {
                currentCoroutineContext().ensureActive()
                throw MeerBotError.Network(e.message ?: "io")
            }

            response.use {
                if (!it.isSuccessful) {
                    val error = decodeError(it.code, it.body?.string())
                    if (allowRetry && error.isExpiredToken) {
                        invalidateToken()
                        runStream(text, allowRetry = false, collector = collector, startedIn = startedIn)
                        return
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
            }
        } finally {
            cancelHandle?.dispose()
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
    private class RawResponse(val code: Int, val successful: Boolean, val text: String?)

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
            RawResponse(it.code, it.isSuccessful, text)
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
