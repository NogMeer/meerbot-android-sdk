package ru.meerbot.sdk

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import ru.meerbot.sdk.network.ApiClient
import ru.meerbot.sdk.network.MeerBotConfiguration
import ru.meerbot.sdk.network.MeerBotError
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
 * MeerBot.setPushToken(fcmToken)                     // если нужны пуши
 * ```
 *
 * Контракт совпадает с iOS SDK (docs/mobile-sdk/api-reference.md).
 */
@SuppressLint("StaticFieldLeak")
object MeerBot {

    const val API_BASE_URL: String = MeerBotConfiguration.DEFAULT_BASE_URL

    /** Версия SDK. Единственный источник — `SDK_VERSION` в gradle.properties. */
    const val VERSION: String = BuildConfig.SDK_VERSION

    private const val PREF_NAME = "meerbot_sdk"
    private const val PREF_NAME_ENCRYPTED = "meerbot_sdk_secure"
    private const val KEY_VISITOR_UUID = "visitor_uuid"
    private const val TAG = "MeerBot"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var prefs: SharedPreferences? = null
    private var configuration: MeerBotConfiguration? = null
    private var client: ApiClient? = null
    private var controller: ChatController? = null
    private var visitorUuid: String? = null

    /** FCM-токен, полученный до configure() — зарегистрируем, как только появится конфигурация. */
    private var pendingPushToken: String? = null

    /**
     * Настроить SDK.
     *
     * @param apiKey `pk_live_*` headless-виджета из кабинета (Бот → Каналы) — транспорт чата.
     *   Строка `origin` (по умолчанию `https://<applicationId>`) обязана быть в списке
     *   разрешённых доменов этого ключа, иначе handshake вернёт `key_invalid`.
     * @param pushApiKey `pk_live_*` мобильного приложения — нужен ТОЛЬКО для пушей: JWT из
     *   `/mobile/register` чат-эндпоинтом не принимается. Без пушей параметр не нужен.
     * @param origin переопределение заголовка `Origin`.
     * @param baseUrl адрес платформы (для стенда).
     */
    @JvmOverloads
    fun configure(
        context: Context,
        apiKey: String,
        pushApiKey: String? = null,
        origin: String? = null,
        baseUrl: String = API_BASE_URL,
    ) {
        configure(
            context,
            MeerBotConfiguration(
                apiKey = apiKey,
                pushApiKey = pushApiKey,
                baseUrl = baseUrl,
                origin = origin ?: MeerBotConfiguration.defaultOrigin(context),
            ),
        )
    }

    /** Настройка целиком объектом конфигурации (тесты и хост-приложения со своим OkHttp). */
    @JvmOverloads
    fun configure(
        context: Context,
        configuration: MeerBotConfiguration,
        httpClient: OkHttpClient = ApiClient.defaultHttpClient(),
    ) {
        val appContext = context.applicationContext
        prefs = openPrefs(appContext)
        val uuid = getOrCreateVisitorUuid()
        val apiClient = ApiClient(configuration, uuid, httpClient)

        this.configuration = configuration
        this.visitorUuid = uuid
        this.client = apiClient
        this.controller = ChatController(apiClient, scope)

        // Handshake здесь СОЗНАТЕЛЬНО не делаем: `/widget/session` заводит строку визитора,
        // и рукопожатие на старте приложения записало бы «посетителя» каждому, кто чат ни разу
        // не открыл, — это перекосило бы аналитику владельца. Кому нужен прогрев — preconnect().

        pendingPushToken?.let { token ->
            pendingPushToken = null
            setPushToken(token)
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
            )
        }
    }

    /** Контроллер чата — для приложений, которые рисуют свой UI поверх нашего состояния. */
    fun chatController(): ChatController? = controller

    /**
     * Открыть сессию заранее, чтобы первый экран чата открылся без сетевой паузы. Побочный
     * эффект — визитор появится в аналитике владельца, даже если чат так и не откроют.
     */
    fun preconnect() {
        controller?.start()
    }

    /**
     * Зарегистрировать FCM-токен (из `FirebaseMessaging.getInstance().token` или
     * `onNewToken`). Требует `pushApiKey` в configure; без него вызов игнорируется с записью
     * в лог. Firebase SDK внутрь библиотеки не тянется — токен приходит уже готовым.
     */
    fun setPushToken(token: String) {
        val apiClient = client
        val config = configuration
        if (apiClient == null || config == null) {
            // configure() ещё не вызван — запомним и зарегистрируем после.
            pendingPushToken = token
            return
        }
        if (config.pushApiKey.isNullOrEmpty()) {
            Log.w(TAG, "setPushToken проигнорирован: не задан pushApiKey в configure(...)")
            return
        }
        scope.launch {
            try {
                apiClient.registerDevice(token)
            } catch (e: MeerBotError) {
                // Пуши — не критичный путь: чат работает и без них. Но молча не глотаем.
                Log.w(TAG, "регистрация FCM-токена не удалась: ${e.code}", e)
            }
        }
    }

    /**
     * Обработать входящий пуш (из `FirebaseMessagingService.onMessageReceived`).
     * Возвращает `true`, если пуш наш и обработан. Полезная нагрузка: `{"conversationId": "123"}`.
     */
    fun handlePush(data: Map<String, String>): Boolean {
        val conversationId = data["conversationId"]?.toLongOrNull() ?: return false
        val current = controller ?: return false
        current.openConversation(conversationId)
        return true
    }

    /**
     * Сбросить состояние SDK (GDPR Art. 17 на стороне клиента): визитор, история, токены.
     * Серверные данные удаляет `POST /api/v1/widget/visitor/forget` — отдельный вызов.
     */
    fun reset() {
        controller?.stop()
        controller?.store?.resetForLogout()
        client = null
        controller = null
        configuration = null
        visitorUuid = null
        pendingPushToken = null
        prefs?.edit()?.clear()?.apply()
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

    private fun getOrCreateVisitorUuid(): String {
        val store = prefs
        val existing = store?.getString(KEY_VISITOR_UUID, null)
        // Сервер валидирует visitorUuid ровно по длине 36 — мусор из старых версий отбрасываем.
        if (existing != null && existing.length == 36) return existing
        val fresh = UUID.randomUUID().toString()
        store?.edit()?.putString(KEY_VISITOR_UUID, fresh)?.apply()
        return fresh
    }
}
