# MeerBot Android SDK

Экран чата с ИИ-ассистентом MeerBot внутри вашего Android-приложения: Compose-экран,
потоковые ответы (SSE), эскалация на менеджера, догон истории после обрыва связи,
регистрация устройства для пушей.

**Статус:** `0.1.0-alpha` — рабочий чат, паритет с iOS SDK по контракту и поведению.
Не сделано: вложения, verified identity (HMAC), Play Integrity, cert pinning, доставка пушей.
См. [Границы](#границы-текущей-версии).

---

## Требования

- Android 7.0 (API 24) и выше
- Kotlin 1.9+, Jetpack Compose (BOM 2024.02+), JDK 17 для сборки
- Аккаунт в кабинете MeerBot с настроенным ассистентом

---

## Установка

```kotlin
dependencies {
    implementation("ru.meerbot:sdk:0.1.0-alpha")
}
```

> Зеркала репозитория, в отличие от iOS, здесь нет и не нужно: Gradle ставит **артефакт**,
> а не репозиторий. Сегодня артефакт собирается локально
> (`scripts/release-android-sdk.sh 0.1.0` → `sdk/build/repo`), публичный Maven ещё не
> подключён — см. [Публикация](#публикация).

---

## Настройка в кабинете (обязательный шаг)

1. **Кабинет → Бот → Каналы → Виджеты → Создать**, тип — **headless** (свой UI, наш API).
2. В «Разрешённые домены» добавить строку **`https://<applicationId>`** вашего приложения,
   например `https://ru.tumanvpn.app`.
   Это не сайт — это значение заголовка `Origin`, которым SDK помечает свои запросы
   (по умолчанию строится из `applicationId`). Кабинет принимает только `https://`-origin,
   поэтому схема `meerbot://` не подойдёт. Если удобнее — укажите домен своего сайта
   и передайте его в `origin` при настройке.
3. Скопировать показанный один раз ключ `pk_live_…`.

---

## Быстрый старт

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        MeerBot.configure(this, apiKey = "pk_live_…")
    }
}

class SupportActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                MeerBot.ChatScreen(onClose = { finish() })
            }
        }
    }
}
```

Сессия открывается при первом показе экрана, а не в `configure(...)`: рукопожатие заводит
запись посетителя, и делать его на старте приложения означало бы приписывать владельцу
«посетителей», которые чат не открывали. Нужен прогрев — `MeerBot.preconnect()`.
Если `configure` не вызвали, `ChatScreen()` покажет явное сообщение об ошибке, а не пустоту.

Экран берёт цвета из `MaterialTheme.colorScheme` хост-приложения — светлая и тёмная темы
и Material You работают сами. Акцент можно переопределить: `MeerBot.ChatScreen(primaryColor = …)`.

### Свой UI поверх нашего состояния

```kotlin
val controller = MeerBot.chatController() ?: return
val state by controller.state.collectAsState()   // messages / mode / sending / connectionError
controller.start()
controller.send("текст")
controller.retry()
controller.openConversation(id)
```

### Строки и локализация

Все тексты экрана — ресурсы с префиксом `meerbot_` (`values` — русский, `values-en` —
английский). Любую строку можно переопределить, объявив ресурс с тем же именем в своём
приложении.

---

## Пуш-уведомления

Firebase SDK внутрь библиотеки не тянется — токен приходит уже готовым из вашего приложения:

```kotlin
class MyMessagingService : FirebaseMessagingService() {
    override fun onNewToken(token: String) {
        MeerBot.setPushToken(token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        // true — пуш наш, чат открыт на нужном диалоге
        MeerBot.handlePush(message.data)
    }
}
```

### Два ключа — сегодняшнее ограничение бэкенда

Пуши требуют **второго** ключа: **Кабинет → Бот → Каналы → Мобильные приложения → Создать**,
затем `MeerBot.configure(context, apiKey = "<ключ виджета>", pushApiKey = "<ключ приложения>")`.

Почему не один ключ: `POST /api/v1/mobile/register` выдаёт JWT, подписанный на пространство id
мобильного приложения, а `POST /api/v1/widget/chat/stream` требует токен канала
website_widget — чат по mobile-ключу получит `401 jwt_channel_mismatch`. Пока бэкенд не свяжет
мобильное приложение с каналом чата, чат и пуши живут на разных ключах.
**Сама доставка пушей на бэкенде — заглушка**, так что регистрация токена сегодня ничего
не доставляет; она нужна, чтобы устройство уже было известно платформе.

---

## Что делает SDK

| Возможность | Как работает |
|---|---|
| Потоковый ответ | `POST /api/v1/widget/chat/stream`, SSE; текст появляется по мере генерации |
| Ответ менеджера | событие `manager_message` в том же потоке (режим `pending_escalation`/`human`) |
| Эскалация | событие `escalation` → `state.mode` переключается, UI это отражает |
| Обрыв связи | частичный текст сохраняется, показывается баннер с «Повторить»; если сервер успел дописать ответ — лента перечитывается через `GET /api/v1/widget/messages` |
| Протухший токен | 15-минутный JWT обновляется прозрачно, запрос повторяется ровно один раз |
| Пуш → диалог | `handlePush` открывает нужный `conversationId` и подтягивает историю |
| Сброс | `MeerBot.reset()` — визитор, история и токены на устройстве |

Идентификатор посетителя (`visitorUuid`) хранится в `EncryptedSharedPreferences`
(с миграцией из открытых prefs старых версий); JWT живёт только в памяти — 15 минут,
обновляется handshake'ом.

---

## Границы текущей версии

- **Вложения** (`/widget/upload`) не поддержаны — только текст.
- **Verified identity** (HMAC-подпись внешнего userId) не реализована: посетитель анонимный.
  Виджет с `requireIdentity=true` ответит `403 identity_required`.
- **Play Integrity / cert pinning** — не реализованы; на бэкенде проверка аттестации тоже
  заглушка (`/api/v1/mobile/attestation` принимает любой непустой токен).
- **Пуши** — регистрация устройства работает, доставка на бэкенде не реализована.
- **`ChatMode.Closed`** блокирует ввод, но сервер этот режим сегодня не присылает.
- Публикация в публичный Maven не настроена — артефакт собирается локально.

---

## Разработка

```bash
./gradlew :sdk:assembleRelease      # сборка библиотеки
./gradlew :sdk:testDebugUnitTest    # 55 unit-тестов
./gradlew :sdk:connectedDebugAndroidTest  # 4 теста экрана на устройстве/эмуляторе
./gradlew :demo:installDebug        # демо-приложение на устройство/эмулятор
```

Тесты не ходят в интернет: транспорт подменяется `MockWebServer`, который умеет отдавать
SSE кусками и рвать соединение посреди потока. Инструментальные тесты проверяют то, что
unit-тесты не видят: что экран действительно подключён к состоянию.

Перед релизом полезно собрать демо в release (`:demo:assembleRelease`) — это единственный
прогон R8 с нашими consumer-правилами, то есть ровно то, что произойдёт у интегратора.

### Структура

```
sdk/src/main/kotlin/ru/meerbot/sdk/
  MeerBot.kt                        публичный фасад
  network/MeerBotConfiguration.kt   ключи, origin, базовый адрес
  network/ApiClient.kt              handshake, JWT, SSE-поток, история, регистрация устройства
  network/SseReader.kt              построчный разбор event-stream
  network/ChatStreamEvent.kt        отображение событий сервера в типы SDK
  network/MeerBotError.kt           коды ошибок платформы и тексты для пользователя
  state/ChatController.kt           поведение: отправка, обрыв, повтор, догон истории
  state/ChatStore.kt                наблюдаемое состояние экрана
  state/ChatViewModel.kt            тонкая обёртка для Compose
  ui/                               Compose: ChatScreen, NotConfiguredScreen
demo/                               демо-приложение (аналог Example у iOS)
```

### Публикация

```bash
scripts/release-android-sdk.sh 0.1.0 --dry-run   # только проверки
scripts/release-android-sdk.sh 0.1.0             # собрать артефакт в sdk/build/repo
```

Гейты: чистое рабочее дерево `mobile-sdk-android`, `SDK_VERSION` в `gradle.properties`
совпадает с публикуемой версией, в `CHANGELOG.md` есть раздел о ней, сборка и тесты зелёные.
Тег `android-X.Y.Z` ставится вручную и запускает `release-guard` в CI.

### Контракт с бэкендом

Источник правды — `agentbot-platform`:
`src/app/api/v1/widget/{session,chat/stream,messages}/route.ts`,
`src/app/api/v1/mobile/register/route.ts`.
Эталонные клиенты того же протокола — `mobile-sdk-ios` и веб-виджет
(`widget/src/chat/api/stream.ts`). Меняется контракт — синхронно правятся все три.
