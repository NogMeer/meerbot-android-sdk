package ru.meerbot.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.annotation.MainThread
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Все изменения состояния SDK — через него (см. [MainThreadSerialExecutor]). */
    private val mainThread by lazy { MainThreadSerialExecutor.forMainLooper() }

    // Пишутся только на главном потоке; @Volatile — для чтения с любого (`identityStatus()`,
    // `chatController()`, `handlePush`).
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
     * хост вырезал провайдер контекста, в этом процессе сигнал всё равно не потеряется.
     */
    @Volatile
    private var pendingLogout = false

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
        // Провайдер уже дал контекст при старте процесса; здесь — для хоста, вырезавшего его.
        EarlyLogout.attach(appContext)
        mainThread.execute { applyConfiguration(store, configuration, httpClient) }
    }

    @MainThread
    private fun applyConfiguration(
        store: SharedPreferences,
        configuration: MeerBotConfiguration,
        httpClient: OkHttpClient,
    ) {
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
        if (pendingLogout || EarlyLogout.isPending()) {
            coordinator.apply(null)
            pendingLogout = false
            EarlyLogout.clear()
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
     * Что делает вызов, решает `sub` токена (у нечитаемого токена — вся строка) в сравнении с
     * последним применённым на этой установке. SDK хранит его хеш, поэтому сравнение переживает
     * перезапуск приложения:
     * - **тот же пользователь** (свежий токен на очередной вход в чат) — токен уходит в
     *   следующее рукопожатие, лента не трогается;
     * - **первый вход** (токена не было или был выход) — токен уходит в рукопожатие, лента
     *   очищается;
     * - **другой пользователь без выхода прежнего** — это выход плюс вход: следующее
     *   рукопожатие несёт `logout: true` и новый токен, диалог, курсор и статус identity
     *   прежнего сбрасываются, лента очищается. Новый человек не увидит тред прежнего, даже
     *   если его токен сервер не примет (например, просрочен);
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
     * До `configure(...)` вызов запоминается и применяется при настройке. Выход к тому же сразу
     * пишется на диск (контекст приложения SDK получает при старте процесса своим
     * ContentProvider) и переживает перезапуск, даже если `configure` в этом процессе так и не
     * позовут. Если хост вырезал провайдер из манифеста, выход до `configure` живёт только в
     * памяти процесса.
     */
    fun identify(token: String?) {
        mainThread.execute { applyIdentity(token) }
    }

    @MainThread
    private fun applyIdentity(token: String?) {
        val coordinator = identity
        if (coordinator == null) {
            if (token == null) {
                pendingLogout = true
                if (!EarlyLogout.markPending()) {
                    Log.w(TAG, "logout_not_persisted: выход до configure() без контекста приложения останется только в памяти процесса")
                }
            }
            pendingIdentityToken = token
            return
        }
        coordinator.apply(token)
    }

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
        controller?.stop()
        controller?.store?.resetForLogout()
        client = null
        controller = null
        identity = null
        configuration = null
        visitorUuid = null
        pendingIdentityToken = null
        pendingLogout = false
        prefs?.edit()?.clear()?.apply()
        EarlyLogout.clear()
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
