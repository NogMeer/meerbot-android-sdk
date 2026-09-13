# MeerBot Android SDK

Экран чата с ИИ-ассистентом MeerBot внутри вашего Android-приложения: Compose-экран,
потоковые ответы (SSE), подключение менеджера, догон истории после обрыва связи.

**Статус:** `0.2.9` — работает на собственном канале платформы (`mobile_app`): один
ключ, свои эндпоинты, свой диалог на устройство; выход и смена пользователя отвязывают
устройство. Не сделано: вложения, Play Integrity, cert pinning. См. [Границы](#границы-текущей-версии).

Полная документация: [meerbot.ru/docs/mobile-sdk](https://meerbot.ru/docs/mobile-sdk).

> ### ⚠️ Правки — в `agentbot-platform`, не в репозитории `meerbot-android-sdk`
>
> `github.com/NogMeer/meerbot-android-sdk` — **зеркало**. Его содержимое целиком
> перезаписывается срезом каталога `mobile-sdk-android/` из приватного
> `agentbot-platform`, откуда его публикует CI по тегу `android-X.Y.Z` (ручной аналог —
> `scripts/release-android-sdk-mirror.sh`).
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
    implementation("com.github.NogMeer:meerbot-android-sdk:0.2.9")
}
```

> Артефакт собирает **JitPack** — из зеркала по тегу версии, поэтому в `settings.gradle.kts`
> нужен репозиторий `https://jitpack.io`. Локальная сборка артефакта
> (`scripts/release-android-sdk.sh <версия>` → `sdk/build/repo`) осталась для отладки —
> см. [Публикация](#публикация).

---

## Настройка в кабинете

**Кабинет → Бот → Каналы → Мобильные приложения → Создать**, платформа — Android.
Скопировать показанный один раз ключ `pk_live_…`. Для verified identity — сохранить и
«Секрет для identity-токена» (тоже один раз; потерян — «Перевыпустить секрет identity») и
передать разработчику бэкенда.

Ключ ровно один, «разрешённые домены» заполнять не нужно: это настройка веб-виджета,
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
// Токен выпускает ВАШ бэкенд «Секретом для identity-токена» этого приложения
MeerBot.identify(identityToken)   // перед открытием чата, токен только что получен
MeerBot.identify(null)            // только при выходе из аккаунта
MeerBot.identityStatus()          // verified / stale / rejected / not_configured / not_provided
```

Пока токен не передан, посетитель анонимен: инструменты ассистента с доступом к данным
клиента ему недоступны. Провал проверки **не роняет** чат — сессия останется анонимной, а
причина видна в `identityStatus()`, а не только в наших логах.

Токен: HS256, `sub` — строка до 255 символов из авторизации запроса к вашей ручке, обязательные
`iat` и `exp` (не дальше 7 суток), без `vid`. Свежесть (не старше 5 минут) проверяется при
рукопожатии — показ экрана, `preconnect()`, обновление сессии раз в 15 минут, — а не при
`identify`, поэтому токен запрашивается прямо перед открытием чата.

**Выход и смена пользователя (с 0.2.9).** `identify(null)` отвязывает устройство на сервере при
следующем рукопожатии: прежний тред остаётся за прежним пользователем, новый начинается
пустым, лента очищается сразу. Токен другого пользователя отвязывает прежнего и без выхода;
свежий токен того же пользователя ленту не трогает; повторный вход со свежим токеном
возвращает прежний тред. Сигнал выхода переживает перезапуск, в том числе запрошенный до
`configure` — контекст приложения SDK получает своим `ContentProvider`
(`ru.meerbot.sdk.internal.MeerBotContextProvider`). Сервер, не знающий выхода, сигнал
игнорирует — там устройство остаётся за последним вошедшим, как в 0.2.8. Зовите `null`
**только на настоящем выходе**.

**Потоки (с 0.2.9).** `configure`, `identify`, `reset` и `preconnect` можно звать с любого
потока: действие применяется на главном строго в порядке вызова, с фонового — асинхронно
(`chatController()` сразу после `configure` с фонового потока может вернуть `null`). Методы
`ChatController` — только с главного потока.

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
бэкенд** (`manager_reply`, подпись HMAC «Секретом подписи вебхука», поле `external_user_id`) —
и вы адресуете пуш своему пользователю сами, своими средствами. Приложение, получив пуш,
вызывает:

```kotlin
MeerBot.handlePush(remoteMessage.data)   // ключ conversationId — из conversation_id вебхука
```

Открытый экран чата догоняет ленту сам (в том числе при возврате приложения на передний
план), звать `refresh()` на `ON_RESUME` не нужно. Чтобы не будить пользователя уведомлением о
диалоге, который открыт у него на экране, сравните `conversation_id` из вебхука с
`MeerBot.chatController()?.conversationId`.

Контракт вебхука — [meerbot.ru/docs/mobile-sdk/webhook](https://meerbot.ru/docs/mobile-sdk/webhook).

---

## Что делает SDK

| Возможность | Как работает |
|---|---|
| Потоковый ответ | `POST /api/v1/mobile/chat/stream`, SSE; текст появляется по мере генерации |
| Ответ менеджера | событие `manager_message` в открытом потоке; вне потока — `handlePush`/`refresh()` после вебхука |
| Эскалация | событие `escalation` → `state.mode` переключается, UI это отражает |
| Обрыв связи | частичный текст сохраняется, показывается баннер с «Повторить»; если сервер успел дописать ответ — лента перечитывается через `GET /api/v1/mobile/messages` |
| Протухшая сессия | 15-минутный JWT обновляется прозрачно, запрос повторяется ровно один раз; с 0.2.9 так же — `device_not_found` / `device_claim_missing` |
| Идентификация | `identify(token)` — подпись вашего бэкенда, статус наружу; `identify(null)` — выход |
| Сброс | `MeerBot.reset()` — установка, визитор, лента, токены и неотправленный выход на устройстве |

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
- **`ChatMode.Closed`** блокирует ввод; режим приходит вместе с историей.
- Своего Maven у нас нет — артефакт собирает JitPack из зеркала по тегу версии.

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
  internal/                         очередь главного потока, ContentProvider контекста
  network/MeerBotConfiguration.kt   ключ, адрес платформы, статусы identity
  network/ApiClient.kt              рукопожатие, JWT, SSE-поток, история, выход
  network/IdentityCoordinator.kt    что значит очередной identify: вход, обновление, смена, выход
  network/SseReader.kt              построчный разбор event-stream
  network/ChatStreamEvent.kt        отображение событий сервера в типы SDK
  network/MeerBotError.kt           коды отказов канала и тексты для пользователя
  state/ChatController.kt           поведение: отправка, обрыв, повтор, догон истории
  state/ChatStore.kt                наблюдаемое состояние экрана
  ui/                               Compose: ChatScreen, NotConfiguredScreen
demo/                               демо-приложение (аналог Example у iOS)
```

### Публикация

Выпуск — тег `android-X.Y.Z` в `agentbot-platform`: `.github/workflows/android-sdk-ci.yml`
прогоняет сборку, unit-тесты, lint, релизную сборку демо и RN-моста, сверяет тег с
`SDK_VERSION` и `CHANGELOG.md` и публикует срез в зеркало с тегом `X.Y.Z` — его и собирает
JitPack.

```bash
scripts/release-android-sdk-mirror.sh 0.2.9 --dry-run   # ручной аналог публикации, только проверки
scripts/release-android-sdk.sh 0.2.9                    # собрать артефакт локально в sdk/build/repo
```

### Контракт с бэкендом

Источник правды — `agentbot-platform`:
`src/app/api/v1/mobile/{register,chat/stream,messages}/route.ts` и
`src/app/api/v1/mobile/chat/_lib/context.ts`. Контракт SSE у мобильного канала и веб-виджета
общий; iOS SDK работает на том же канале.
