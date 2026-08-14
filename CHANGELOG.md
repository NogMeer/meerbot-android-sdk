# Changelog

Формат — [Keep a Changelog](https://keepachangelog.com/ru/1.1.0/), версионирование —
[semver](https://semver.org/lang/ru/). До `1.0.0` минорная версия может ломать API
(см. `docs/mobile-sdk/versioning.md`).

## [0.1.0-alpha] — 2026-08-14

Первая работающая версия: паритет с iOS SDK `0.1.0` по контракту и поведению.

### Добавлено
- Публичный фасад `MeerBot`: `configure`, `ChatScreen`, `chatController`, `preconnect`,
  `setPushToken`, `handlePush`, `reset`; экран «SDK не настроен» вместо пустоты.
- Сетевой слой `ApiClient`: handshake `/api/v1/widget/session`, потоковый чат
  `/api/v1/widget/chat/stream`, догон истории `/api/v1/widget/messages`, регистрация
  устройства `/api/v1/mobile/register`.
- Типизированные события стрима (`ChatStreamEvent`) и коды ошибок платформы (`MeerBotError`).
- `ChatController` + `ChatStore`: приветствие канала, повтор недоставленного сообщения,
  замена ленты серверной историей после обрыва, реакция на `heartbeat`/`timeout`/`shutdown`.
- Compose-экран: светлая и тёмная темы через `colorScheme`, строки ресурсами (ru + en),
  метка «Не отправлено» и баннер с «Повторить», уважение системного «убрать анимации».
- Демо-приложение `demo/` с настраиваемыми ключами и адресом платформы.
- 55 unit-тестов на MockWebServer; CI (`.github/workflows/android-sdk-ci.yml`) со сборкой,
  тестами и проверкой соответствия тега версии; `scripts/release-android-sdk.sh`.

### Исправлено (относительно каркаса Phase 5.b/c)
- Модуль не собирался: не было `settings.gradle.kts`, wrapper и `consumer-rules.pro`,
  на который ссылался `defaultConfig`.
- Тело запроса чата отправлялось полем `content`; бэкенд принимает `message` — чат
  гарантированно получал `400 message_required`.
- Стрима фактически не было: события копились в список и отдавались после закрытия
  соединения.
- Публичный `MeerBot.ChatScreen` не был связан с реализацией, а состояние — с сетью
  (`sendDemo` отвечал захардкоженной строкой).
- Завершение потока определялось по несуществующим событиям `done`/`close` вместо
  безымянного `data: [DONE]`.
- `visitorUuid` хранился в открытых `SharedPreferences` (теперь `EncryptedSharedPreferences`
  с миграцией), заголовок `X-SDK-Version` не отправлялся вовсе.
- Версия SDK была объявлена в трёх местах и расходилась — теперь одна константа
  (`SDK_VERSION` в `gradle.properties` → `BuildConfig` → `MeerBot.VERSION`).
- Легаси-поле `externalUserId` в handshake сервер игнорирует — убрано, чтобы не создавать
  иллюзию переданной identity.

### Изменено
- 401 `jwt_channel_mismatch` больше не считается «протухшим токеном»: повтор его не лечит
  (перепутан ключ), запрос падает сразу с понятным кодом.
- Из зависимостей убраны `firebase-messaging` и `play:integrity`: SDK принимает готовый
  FCM-токен строкой и не тянет Firebase в приложение-потребитель, а клиентская половина
  аттестации не пишется, пока серверная — заглушка.

### Известные ограничения
- Вложения, verified identity (HMAC), Play Integrity, certificate pinning — не реализованы.
- Доставка пушей не реализована на бэкенде; регистрация токена работает.
- Публикация в публичный Maven не настроена: артефакт собирается в `sdk/build/repo`.
- Сквозной прогон выполнен против локального стенда контракта; прогон по реальному ключу
  прода — открытая задача (`docs/TASKS.md`, Ф6).

[0.1.0-alpha]: https://github.com/NogMeer/agentbot-platform/tree/main/mobile-sdk-android
