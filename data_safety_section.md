# Data Safety — Google Play Console

Эту секцию нужно вручную заполнить в **Play Console → App content → Data safety** при публикации
приложения с интегрированным `ru.meerbot:sdk`.

## Сбор данных

| Категория | Тип | Собирается? | Linked? | Цель |
|---|---|---|---|---|
| Personal info | User IDs (visitorUuid) | ✅ | ❌ (анонимный UUID) | Поддержка приложения |
| Personal info | External user ID (если задан клиентом) | ✅ опционально | ✅ | Поддержка приложения |
| Messages | Тексты сообщений | ✅ | ❌ | Поддержка приложения |
| App activity | Push token (FCM) | ✅ | ❌ | Связь с приложением |
| Device or other IDs | Device token (FCM) | ✅ | ❌ | Связь с приложением |

## Шифрование при передаче
✅ Да — все запросы через HTTPS с certificate pinning (если включён владельцем приложения).

## Можно ли запросить удаление данных
✅ Да — пользователь может запросить удаление через `MeerBot.reset()` (локальные данные)
и через `POST /api/v1/widget/visitor/forget` (серверные данные, GDPR Art. 17).

## Tracking
❌ MeerBot SDK НЕ собирает Advertising ID и не передаёт данные в третьи стороны для рекламы.

## Соответствие
- **GDPR**: Art. 15 (Right to Access — `POST /api/v1/widget/visitor/data`),
  Art. 17 (Right to Erasure — `POST /api/v1/widget/visitor/forget`)
- **152-ФЗ (РФ)**: Согласие на обработку получается через UI хост-приложения.
- **Apple App Tracking Transparency**: Не применимо (SDK не использует IDFA).
- **Google Play Families Policy**: SDK подходит для приложений без ограничений по возрасту
  при условии что владелец приложения сам обеспечивает age gate в host UI.
