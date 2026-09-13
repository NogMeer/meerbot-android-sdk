package ru.meerbot.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import ru.meerbot.sdk.internal.EarlyLogout
import ru.meerbot.sdk.internal.EarlyLogoutFile
import ru.meerbot.sdk.internal.MainThreadSerialExecutor
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.IdentityCoordinator
import ru.meerbot.sdk.network.IdentityStatus
import ru.meerbot.sdk.network.MeerBotConfiguration
import ru.meerbot.sdk.network.PrefsLogoutFlagStore
import ru.meerbot.sdk.network.PrefsSubjectHashStore
import ru.meerbot.sdk.state.ChatController
import ru.meerbot.sdk.ui.NotConfiguredScreen
import java.util.UUID
import ru.meerbot.sdk.ui.ChatScreen as ChatScreenImpl

/**
 * Публичная точка входа Android SDK.
 *
 * Минимальная интеграция:
 * ```
 * MeerBot.configure(context, apiKey = "pk_live_…")   // старт приложения
 * MeerBot.ChatScreen()                               // Compose-экран чата
 * MeerBot.identify(token)                            // если пользователь вошёл
 * ```
 *
 * SDK работает с каналом `mobile_app`: один ключ, свои эндпоинты, свой тред на устройство
 * (docs/mobile-sdk/android.md).
 *
 * Потоки: `configure`, `identify`, `reset` и `preconnect` можно звать с любого потока. Их
 * действие применяется на главном потоке строго в порядке вызова; с главного потока — сразу
 * (если очередь пуста), с фонового — асинхронно, к возврату из метода оно может быть ещё не
 * применено.
 */
@SuppressLint("StaticFieldLeak")
object MeerBot {

    const val API_BASE_URL: String = MeerBotConfiguration.DEFAULT_BASE_URL

    /** Версия SDK. Единственный источник — `SDK_VERSION` в gradle.properties. */
    const val VERSION: String = BuildConfig.SDK_VERSION

    private const val PREF_NAME = "meerbot_sdk"
    private const val PREF_NAME_ENCRYPTED = "meerbot_sdk_secure"
    private const val KEY_VISITOR_UUID = "visitor_uuid"
    private const val KEY_INSTALLATION_ID = "installation_id"
    private const val TAG = "MeerBot"

    // Лениво: `Dispatchers.Main` требует главного Looper, а синглтон создаётся и в JVM-тестах.
    private val scope by lazy { CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate) }

    private val mainLooperExecutor by lazy { MainThreadSerialExecutor.forMainLooper() }

    /**
     * Очередь вместо главного Looper — только для JVM-тестов: так проверяется, что публичные
     * методы действительно идут через очередь, а не меняют состояние на потоке вызывающего.
     */
    @VisibleForTesting
    @Volatile
    internal var executorOverride: MainThreadSerialExecutor? = null

    /** Все изменения состояния SDK — через неё (см. [MainThreadSerialExecutor]). */
    private val mainThread: MainThreadSerialExecutor
        get() = executorOverride ?: mainLooperExecutor

    // Пишутся только на главном потоке; @Volatile — для чтения с любого (`identityStatus()`,
    // `chatController()`, `handlePush`, запись выхода в `identify`).
    @Volatile
    private var prefs: SharedPreferences? = null

    @Volatile
    private var configuration: MeerBotConfiguration? = null

    @Volatile
    private var client: ApiClient? = null

    @Volatile
    private var controller: ChatController? = null

    @Volatile
    private var visitorUuid: String? = null

    /** Решает, что значит очередной `identify`: вход, свежий токен, смена человека, выход. */
    @Volatile
    private var identity: IdentityCoordinator? = null

    /** Токен идентичности, переданный до configure() — применим на первом рукопожатии. */
    @Volatile
    private var pendingIdentityToken: String? = null

    /**
     * Выход, запрошенный до configure() в этом процессе. Дублирует диск ([EarlyLogout]): если
     * контекста приложения до настройки нет, в этом процессе сигнал всё равно не потеряется.
     */
    @Volatile
    private var pendingLogout = false

    /** Отметки раннего выхода, записанные этим процессом до настройки. Только главный поток. */
    private val pendingEarlyMarks = ArrayList<EarlyLogoutFile.Mark>()

    /**
     * Отметки раннего выхода, уже применённые в этом процессе. Только главный поток: повторный
     * `configure`, прочитавший отметку до того, как первый её снял, не повторит выход поверх
     * пользователя, вошедшего между ними.
     */
    private val appliedEarlyMarkers = HashSet<String>()

    /** Как записан выход на потоке вызывающего `identify(null)`. */
    private sealed interface LogoutIntent {
        /** Флаг клиента на диске (SDK был настроен). */
        object InClient : LogoutIntent

        /** Отметка раннего выхода на диске (SDK не был настроен). */
        class Early(val mark: EarlyLogoutFile.Mark) : LogoutIntent

        /** Записать некуда: нет ни клиента, ни контекста приложения. */
        object NotPersisted : LogoutIntent
    }

    /**
     * Настроить SDK.
     *
     * @param apiKey `pk_live_*` мобильного приложения из кабинета: Бот → Каналы →
     *   Мобильные приложения. Ключ ровно один; ключ веб-виджета здесь не подойдёт —
     *   чат канала отвергнет его токен как `channel_mismatch`.
     * @param baseUrl адрес платформы (для стенда).
     */
    @JvmOverloads
    fun configure(
        context: Context,
        apiKey: String,
        baseUrl: String = API_BASE_URL,
    ) {
        configure(context, MeerBotConfiguration(apiKey = apiKey, baseUrl = baseUrl))
    }

    /**
     * Настройка целиком объектом конфигурации (тесты и хост-приложения со своим OkHttp).
     *
     * Хранилище (Keystore, диск) открывается на потоке вызывающего, сама настройка
     * применяется на главном — в общей очереди с `identify` и `reset`.
     */
    @JvmOverloads
    fun configure(
        context: Context,
        configuration: MeerBotConfiguration,
        httpClient: OkHttpClient = ApiClient.defaultHttpClient(),
    ) {
        val appContext = context.applicationContext
        val store = openPrefs(appContext)
        // Инициализатор уже дал контекст при старте процесса; здесь — для хоста, отключившего его.
        EarlyLogout.attach(appContext)
        // Выход до настройки — из прошлого запуска или другого процесса. Читается здесь, с диска.
        val earlyMarkers = EarlyLogout.file.read()
        mainThread.execute { applyConfiguration(store, configuration, httpClient, earlyMarkers) }
    }

    @MainThread
    private fun applyConfiguration(
        store: SharedPreferences,
        configuration: MeerBotConfiguration,
        httpClient: OkHttpClient,
        earlyMarkers: List<String>,
    ) {
        // Повторный configure: прежний клиент делит с новым флаг выхода в тех же prefs, но не
        // счётчики. Ответ, пришедший ему позже, стёр бы флаг на диске, пока новый держит его
        // только в памяти, — и убитый процесс забыл бы выход. Прежний экран останавливается:
        // его поток и догон принадлежат заменённой сессии.
        client?.retire()
        controller?.stop()
        prefs = store
        val uuid = getOrCreate(store, KEY_VISITOR_UUID) { UUID.randomUUID().toString() }
        // Идентификатор установки уходит в `deviceToken` рукопожатия и определяет, чей это
        // тред. Он стабилен и не подменяется пуш-токеном: смена значения означала бы для
        // пользователя новую переписку с нуля.
        val installation = getOrCreate(store, KEY_INSTALLATION_ID) { "and-" + UUID.randomUUID() }
        val apiClient = ApiClient(configuration, uuid, installation, httpClient, PrefsLogoutFlagStore(store))
        val chat = ChatController(apiClient, scope)
        val coordinator = IdentityCoordinator(
            client = apiClient,
            installationId = installation,
            subjects = PrefsSubjectHashStore(store),
            resetFeed = chat::resetForIdentityChange,
        )

        this.configuration = configuration
        this.visitorUuid = uuid
        this.client = apiClient
        this.controller = chat
        this.identity = coordinator

        // Рукопожатие здесь СОЗНАТЕЛЬНО не делаем: `/mobile/register` заводит строку
        // устройства, и вызов на старте приложения записал бы «устройство» каждому, кто чат
        // ни разу не открыл, — это перекосило бы аналитику владельца и его лимиты.
        // Кому нужен прогрев — preconnect().

        // Выход до configure — в этом процессе или в прошлом — применяется раньше токена:
        // `identify(null)`, затем `identify(B)` до настройки дают одно рукопожатие с `logout`
        // и токеном B, как и после неё.
        val early = earlyMarkers.filterNot { it in appliedEarlyMarkers }
        if (pendingLogout || early.isNotEmpty()) {
            // Флаг клиента — на диск синхронно ДО снятия ранней отметки: процесс, убитый между
            // ними, иначе забыл бы выход. Запись редкая — только когда выход до настройки был.
            apiClient.persistLogoutIntent()
            coordinator.apply(null)
            pendingLogout = false
            // Снимаются только прочитанные отметки: записанные позже (другим процессом) остаются.
            early.forEach {
                appliedEarlyMarkers += it
                EarlyLogout.file.clear(it)
            }
            pendingEarlyMarks.forEach {
                appliedEarlyMarkers += it.marker
                EarlyLogout.file.clear(it.marker)
            }
            pendingEarlyMarks.clear()
        }
        pendingIdentityToken?.let { token ->
            pendingIdentityToken = null
            coordinator.apply(token)
        }
    }

    /**
     * Compose-экран чата. До `configure(...)` показывает явное сообщение об ошибке, а не пустоту.
     */
    @Composable
    fun ChatScreen(
        modifier: Modifier = Modifier,
        // null — заголовок берётся из ресурсов SDK и следует локали устройства.
        title: String? = null,
        primaryColor: Color? = null,
        onClose: (() -> Unit)? = null,
        // false — хост показывает чат вкладкой и рисует заголовок сам.
        showHeader: Boolean = true,
    ) {
        // Контроллер читается на каждой композиции: повторный configure(...) (например,
        // смена ключа) должен подхватываться сразу, а не после перезапуска процесса.
        val current = controller
        if (current == null) {
            NotConfiguredScreen(modifier)
        } else {
            ChatScreenImpl(
                controller = current,
                modifier = modifier,
                title = title,
                primaryColor = primaryColor,
                onClose = onClose,
                showHeader = showHeader,
            )
        }
    }

    /**
     * Контроллер чата — для приложений, которые рисуют свой UI поверх нашего состояния.
     * Его методы управления (`start`, `stop`, `send`, …) — только с главного потока.
     */
    fun chatController(): ChatController? = controller

    /**
     * Открыть сессию заранее, чтобы первый экран чата открылся без сетевой паузы. Побочный
     * эффект — визитор появится в аналитике владельца, даже если чат так и не откроют.
     */
    fun preconnect() {
        mainThread.execute { controller?.start() }
    }

    /**
     * Передать подписанный токен идентичности (verified identity) или сообщить о выходе.
     *
     * Токен выпускает БЭКЕНД интегратора секретом мобильного приложения (кабинет → Каналы →
     * Мобильные приложения). Пока он не передан, посетитель анонимен: инструменты с доступом
     * к данным клиента ему недоступны.
     *
     * Что делает вызов, решает `sub` токена в сравнении с последним применённым на этой
     * установке. SDK хранит его хеш, поэтому сравнение переживает перезапуск приложения:
     * - **тот же пользователь** (`sub` читается и совпал; свежий токен на очередной вход в чат) —
     *   токен уходит в следующее рукопожатие, лента не трогается;
     * - **любой другой токен** — другой `sub`, первый вход, вход после выхода, прежний `sub`
     *   неизвестен (вход был на SDK 0.2.8 и старше) или `sub` нового не читается — это выход
     *   плюс вход: следующее рукопожатие несёт `logout: true` и новый токен, диалог, курсор и
     *   статус identity прежнего сбрасываются, лента очищается. Новый человек не увидит тред
     *   прежнего, даже если его токен сервер не примет (просрочен, исчерпан лимит). Тот же
     *   человек, вошедший заново, тред не теряет: сервер сохраняет связь для того же `sub`;
     * - **`null`** — НАСТОЯЩИЙ выход пользователя из аккаунта, и звать его нужно только тогда,
     *   а не «на всякий случай» при пустом токене. С 0.2.9 выход отвязывает устройство на
     *   сервере при следующем подключении: прежний тред остаётся за прежним пользователем,
     *   новый начинается пустым, а локальная лента очищается сразу. Без выхода сервер держит
     *   связь устройства с последним вошедшим пользователем. Сервер, не знающий выхода, сигнал
     *   игнорирует — тогда связь сохраняется, как у SDK 0.2.8 и старше.
     *
     * Потоки: звать можно с любого. Вызовы применяются на главном потоке строго в порядке
     * вызова (вместе с `configure`, `reset`, `preconnect`): `identify(null)` и следом
     * `identify(B)` с фонового потока придут именно так. С фонового потока действие
     * асинхронно — к возврату из метода оно может быть ещё не применено.
     *
     * Выход пишется на диск синхронно, ещё на потоке вызывающего (одна короткая запись на
     * выход), и переживает убийство процесса сразу после вызова. С главного потока это
     * синхронная запись на диск (`commit()`, StrictMode `DiskWrite`) — сознательно: отложенная
     * запись теряла бы выход при убийстве процесса. До `configure(...)` вызов
     * запоминается и применяется при настройке; выход и тогда на диске (контекст приложения SDK
     * получает при старте процесса через `androidx.startup`) и переживает перезапуск, даже если
     * `configure` в этом процессе так и не позовут. Если хост отключил инициализатор SDK, выход
     * до `configure` живёт только в памяти процесса. Так же — в процессе, отличном от
     * основного (`android:process=":remote"`): провайдер `androidx.startup` там не создаётся, и
     * до первого `configure` в этом процессе контекста у SDK нет.
     */
    fun identify(token: String?) {
        val intent = if (token == null) persistLogoutIntent() else null
        mainThread.execute { applyIdentity(token, intent) }
    }

    /**
     * Выход — на диск на потоке вызывающего, ДО постановки в очередь: вызов с фонового потока
     * иначе ложился бы на диск только после прохода очереди главного потока (и `apply()`), и
     * процесс, убитый в этом окне, забыл бы выход.
     */
    private fun persistLogoutIntent(): LogoutIntent {
        client?.let {
            it.persistLogoutIntent()
            return LogoutIntent.InClient
        }
        val mark = EarlyLogout.file.mark() ?: return LogoutIntent.NotPersisted
        return LogoutIntent.Early(mark)
    }

    @MainThread
    private fun applyIdentity(token: String?, intent: LogoutIntent?) {
        val coordinator = identity
        if (coordinator == null) {
            if (token == null) {
                pendingLogout = true
                // Отметку могла снять `reset()`, прошедшая в очереди раньше, либо на момент
                // вызова SDK был настроен и выход лёг во флаг клиента, которого уже нет.
                val mark = (intent as? LogoutIntent.Early)?.mark?.takeIf { EarlyLogout.file.isIntact(it) }
                    ?: EarlyLogout.file.mark()
                if (mark != null) {
                    pendingEarlyMarks += mark
                } else {
                    Log.w(TAG, "logout_not_persisted: выход до configure() без контекста приложения останется только в памяти процесса")
                }
            }
            pendingIdentityToken = token
            return
        }
        if (token != null) {
            coordinator.apply(token)
            return
        }
        // Настройка применилась между вызовом и этой строкой: выход лёг ранней отметкой (или
        // никуда). Флаг клиента — на диск синхронно, и только затем отметка снимается.
        if (intent !is LogoutIntent.InClient) client?.persistLogoutIntent()
        coordinator.apply(null)
        (intent as? LogoutIntent.Early)?.let {
            appliedEarlyMarkers += it.mark.marker
            EarlyLogout.file.clear(it.mark.marker)
        }
    }

    /** Токен, ждущий настройки, — для JVM-тестов порядка вызовов. */
    @VisibleForTesting
    internal val pendingIdentityTokenForTests: String?
        get() = pendingIdentityToken

    /** Что сервер сделал с identity на последнем рукопожатии. */
    fun identityStatus(): IdentityStatus = client?.identityStatus ?: IdentityStatus.NotProvided

    /**
     * Привести ленту к серверной.
     *
     * С 0.2.4 звать это на возврате приложения из фона НЕ нужно: пока экран чата открыт, он
     * догоняет ленту сам, а уход в фон и возврат обрабатывает контроллер. Метод остаётся для
     * сценария, где экран ЗАКРЫТ: бэкенд интегратора получил вебхук `manager_reply`, разбудил
     * приложение пушем, и хост хочет, чтобы к моменту открытия чата лента была свежей.
     *
     * Своей отправки пушей у платформы нет: ответ менеджера уходит вебхуком на бэкенд
     * интегратора, и он же адресует пуш своему пользователю (по `external_user_id`).
     */
    fun refresh() {
        controller?.refresh()
    }

    /**
     * Обработать пуш «менеджер ответил». `true` — пуш наш и лента уже догоняется.
     *
     * Полезная нагрузка — `remoteMessage.data` с ключом `conversationId` (его кладёт бэкенд
     * интегратора из поля `conversation_id` вебхука). Паритет с iOS `handlePush(_:)`.
     *
     * FCM-токен SDK по-прежнему не принимает: пуши отправляет интегратор, и лишний метод
     * означал бы хранилище строки, которое никуда не ведёт.
     */
    @JvmStatic
    fun handlePush(data: Map<String, String>): Boolean {
        val conversationId = data["conversationId"]?.toLongOrNull() ?: return false
        val chatController = controller ?: return false
        // id запоминается, чтобы хост мог сверить его с открытым экраном и не показывать
        // баннер о сообщении, которое человек видит прямо перед собой.
        client?.rememberConversationId(conversationId)
        chatController.refresh()
        return true
    }

    /**
     * Сбросить состояние SDK (GDPR Art. 17 на стороне клиента): идентификатор установки,
     * визитор, лента, токены и неотправленный выход. Серверные данные мобильного канала
     * удаляются по обращению в поддержку — своего эндпоинта у канала пока нет.
     *
     * ⚠️ После сброса устройство для сервера новое: прежняя переписка останется на старом
     * идентификаторе установки и в приложении больше не покажется.
     */
    fun reset() {
        mainThread.execute { applyReset() }
    }

    @MainThread
    private fun applyReset() {
        // Ответ, пришедший сброшенному клиенту, не должен трогать флаг выхода следующей настройки.
        client?.retire()
        controller?.stop()
        controller?.store?.resetForLogout()
        client = null
        controller = null
        identity = null
        configuration = null
        visitorUuid = null
        pendingIdentityToken = null
        pendingLogout = false
        pendingEarlyMarks.clear()
        prefs?.edit()?.clear()?.apply()
        EarlyLogout.file.clearAll()
    }

    // ─── Внутреннее ───────────────────────────────────────────────────────────────────────

    /**
     * Зашифрованные prefs с миграцией из старых открытых.
     *
     * Если Keystore недоступен (встречается на прошивках без рабочего StrongBox), падать
     * нельзя — чат важнее шифрования псевдонимного идентификатора; откатываемся на обычные
     * prefs и говорим об этом в лог.
     */
    private fun openPrefs(context: Context): SharedPreferences {
        val plain = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        val encrypted = runCatching {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                PREF_NAME_ENCRYPTED,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }.getOrElse {
            Log.w(TAG, "EncryptedSharedPreferences недоступны, используем обычные", it)
            return plain
        }

        val legacyUuid = plain.getString(KEY_VISITOR_UUID, null)
        if (legacyUuid != null && encrypted.getString(KEY_VISITOR_UUID, null) == null) {
            encrypted.edit().putString(KEY_VISITOR_UUID, legacyUuid).apply()
            plain.edit().remove(KEY_VISITOR_UUID).apply()
        }
        return encrypted
    }

    /**
     * Прочитать сохранённое значение или создать новое. `visitorUuid` сервер валидирует
     * ровно по длине 36, поэтому мусор из старых версий отбрасывается.
     */
    private fun getOrCreate(store: SharedPreferences, key: String, create: () -> String): String {
        val existing = store.getString(key, null)
        if (!existing.isNullOrEmpty() &&
            (key != KEY_VISITOR_UUID || existing.length == 36)
        ) {
            return existing
        }
        val fresh = create()
        store.edit().putString(key, fresh).apply()
        return fresh
    }
}
