# MeerBot Android SDK

Экран чата с ИИ-ассистентом MeerBot внутри вашего Android-приложения: Compose-экран,
потоковые ответы (SSE), подключение менеджера, догон истории после обрыва связи.

**Статус:** `0.2.6` — работает на собственном канале платформы (`mobile_app`): один
ключ, свои эндпоинты, свой диалог на устройство. Не сделано: вложения, Play Integrity,
cert pinning. См. [Границы](#границы-текущей-версии).

> ### ⚠️ Правки — в `agentbot-platform`, не в репозитории `meerbot-android-sdk`
>
> `github.com/NogMeer/meerbot-android-sdk` — **зеркало**. Его содержимое целиком
> перезаписывается срезом каталога `mobile-sdk-android/` из приватного
> `agentbot-platform`, откуда его публикует `scripts/release-android-sdk-mirror.sh`.
>
> Коммит, сделанный прямо в зеркале, обратно не возвращается: следующий выпуск версии
> соберёт срез **без него**, а до того момента релиз будет падать на `non-fast-forward`.
> Уже поправили в зеркале? Верните правки в исходник — из чекаута `agentbot-platform`:
> `scripts/sync-sdk-mirror-back.sh android`.

---

## Требования

- Android 7.0 (API 24) и выше
- Kotlin 1.9+, Jetpack Compose (BOM 2024.02+), JDK 17 для сборки
- Аккаунт в кабинете MeerBot с настроенным ассистентом

---

## Установка

```kotlin
dependencies {
    implementation("com.github.NogMeer:meerbot-android-sdk:0.2.6")
}
```

> Артефакт собирает **JitPack** — из зеркала по тегу версии, поэтому в `settings.gradle.kts`
> нужен репозиторий `https://jitpack.io`. Локальная сборка артефакта
> (`scripts/release-android-sdk.sh <версия>` → `sdk/build/repo`) осталась для отладки —
> см. [Публикация](#публикация).

---

## Настройка в кабинете

**Кабинет → Бот → Каналы → Мобильные приложения → Создать**, платформа — Android.
Скопировать показанный один раз ключ `pk_live_…`.

Всё. Ключ ровно один, «разрешённые домены» заполнять не нужно: это настройка веб-виджета,
а мобильное приложение — отдельный канал со своими эндпоинтами.

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

Рукопожатие происходит при первом показе экрана, а не в `configure(...)`: оно заводит запись
устройства, и вызов на старте приложения записал бы «устройство» каждому, кто чат ни разу не
открыл, — это перекосило бы аналитику владельца и его лимиты. Нужен прогрев —
`MeerBot.preconnect()`. Если `configure` не вызвали, `ChatScreen()` покажет явное сообщение
об ошибке, а не пустоту.

Экран берёт цвета из `MaterialTheme.colorScheme` хост-приложения — светлая и тёмная темы и
Material You работают сами. Акцент переопределяется: `MeerBot.ChatScreen(primaryColor = …)`.

### Вход пользователя (verified identity)

```kotlin
// Токен выпускает ВАШ бэкенд секретом приложения (кабинет → Каналы → Мобильные приложения)
MeerBot.identify(identityToken)   // после входа
MeerBot.identify(null)            // после выхода — следующая сессия анонимна
MeerBot.identityStatus()          // verified / rejected / stale / not_configured / not_provided
```

Пока токен не передан, посетитель анонимен: инструменты ассистента с доступом к данным
клиента ему недоступны. Провал проверки **не роняет** чат — сессия останется анонимной, а
причина видна в `identityStatus()`, а не только в наших логах.

### Свой UI поверх нашего состояния

```kotlin
val controller = MeerBot.chatController() ?: return
val state by controller.state.collectAsState()   // messages / mode / sending / connectionError
controller.start()
controller.send("текст")
controller.retry()
controller.refresh()
controller.conversationId                        // непрозрачен, только в паре с каналом
```

### Строки и локализация

Все тексты экрана — ресурсы с префиксом `meerbot_` (`values` — русский, `values-en` —
английский). Любую строку можно переопределить, объявив ресурс с тем же именем в своём
приложении.

---

## Уведомления о новых ответах

Своей отправки пушей у платформы нет. Когда менеджер отвечает, MeerBot шлёт **вебхук на ваш
бэкенд** (`manager_reply`, подпись HMAC, поле `external_user_id`) — и вы адресуете пуш своему
пользователю сами, своими средствами. Приложение, получив пуш, вызывает:

```kotlin
MeerBot.refresh()   // привести ленту к серверной
```

Так же стоит делать при возврате приложения на передний план. Чтобы не будить пользователя
уведомлением о диалоге, который открыт у него на экране, сравните `conversation_id` из
вебхука с `MeerBot.chatController()?.conversationId`.

Контракт вебхука — `swarm-report/mobile-channel-delivery1-2026-08-14.md`.

---

## Что делает SDK

| Возможность | Как работает |
|---|---|
| Потоковый ответ | `POST /api/v1/mobile/chat/stream`, SSE; текст появляется по мере генерации |
| Ответ менеджера | событие `manager_message` в открытом потоке; вне потока — `refresh()` после вебхука |
| Эскалация | событие `escalation` → `state.mode` переключается, UI это отражает |
| Обрыв связи | частичный текст сохраняется, показывается баннер с «Повторить»; если сервер успел дописать ответ — лента перечитывается через `GET /api/v1/mobile/messages` |
| Протухший токен | 15-минутный JWT обновляется прозрачно, запрос повторяется ровно один раз |
| Идентификация | `identify(token)` — подпись вашего бэкенда, статус наружу |
| Сброс | `MeerBot.reset()` — установка, визитор, лента и токены на устройстве |

Идентификатор установки и `visitorUuid` хранятся в `EncryptedSharedPreferences`; JWT живёт
только в памяти — 15 минут, обновляется рукопожатием.

⚠️ Диалог привязан к **установке приложения**: `MeerBot.reset()` и переустановка начинают
переписку с чистого листа, прежняя останется на старом идентификаторе.

---

## Границы текущей версии

- **Вложения** не поддержаны — только текст.
- **Play Integrity / cert pinning** — не реализованы; на бэкенде проверка аттестации тоже
  заглушка (`/api/v1/mobile/attestation` принимает любой непустой токен).
- **Приветствие и заголовок канала** рукопожатие не отдаёт — экран показывает свой дефолт.
- **Эскалация в инбокс**: событие до клиента доходит, но обращение пока не поднимается в
  таб «Нужен человек» — это открытый пункт бэкенда.
- **`ChatMode.Closed`** блокирует ввод; режим приходит вместе с историей.
- Публикация в публичный Maven не настроена — артефакт собирается локально.

---

## Разработка

```bash
./gradlew :sdk:assembleRelease            # сборка библиотеки
./gradlew :sdk:testDebugUnitTest          # unit-тесты
./gradlew :sdk:connectedDebugAndroidTest  # тесты экрана на устройстве/эмуляторе
./gradlew :demo:installDebug              # демо-приложение
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
  network/MeerBotConfiguration.kt   ключ, адрес платформы, статусы identity
  network/ApiClient.kt              рукопожатие, JWT, SSE-поток, история
  network/SseReader.kt              построчный разбор event-stream
  network/ChatStreamEvent.kt        отображение событий сервера в типы SDK
  network/MeerBotError.kt           коды отказов канала и тексты для пользователя
  state/ChatController.kt           поведение: отправка, обрыв, повтор, догон истории
  state/ChatStore.kt                наблюдаемое состояние экрана
  ui/                               Compose: ChatScreen, NotConfiguredScreen
demo/                               демо-приложение (аналог Example у iOS)
```

### Публикация

```bash
scripts/release-android-sdk.sh 0.2.0 --dry-run   # только проверки
scripts/release-android-sdk.sh 0.2.0             # собрать артефакт в sdk/build/repo
```

Гейты: чистое рабочее дерево `mobile-sdk-android`, `SDK_VERSION` в `gradle.properties`
совпадает с публикуемой версией, в `CHANGELOG.md` есть раздел о ней, сборка и тесты зелёные.
Тег `android-X.Y.Z` ставится вручную и запускает `release-guard` в CI.

### Контракт с бэкендом

Источник правды — `agentbot-platform`:
`src/app/api/v1/mobile/{register,chat/stream,messages}/route.ts` и
`src/app/api/v1/mobile/chat/_lib/context.ts`.
План канала и контракт вебхука — `swarm-report/mobile-channel-delivery1-2026-08-14.md`.
iOS SDK переезжает на этот же канал (этап F плана); контракт SSE у каналов общий, поэтому
расходиться должны только адреса и ключ.
