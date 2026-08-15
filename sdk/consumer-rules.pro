# Правила, которые SDK навязывает приложению-потребителю.
#
# Разбор ответов идёт через org.json по именам полей, но сами поля лежат в data-классах,
# которые R8 волен переименовывать — рефлексии здесь нет. Поэтому единственное, что
# действительно нужно сохранить, — публичный API SDK: имена, которые пишет интегратор.

-keep public class ru.meerbot.sdk.MeerBot { public *; }
-keep public class ru.meerbot.sdk.network.MeerBotConfiguration { public *; }
-keep public class ru.meerbot.sdk.network.MeerBotError { public *; }
-keep public class ru.meerbot.sdk.network.MeerBotError$* { public *; }
-keep public class ru.meerbot.sdk.state.ChatController { public *; }
-keep public class ru.meerbot.sdk.state.ChatMessage { public *; }
-keep public class ru.meerbot.sdk.state.ChatState { public *; }
-keep public enum ru.meerbot.sdk.state.ChatMode { *; }

# OkHttp тянет за собой опциональные ссылки на Conscrypt/BouncyCastle/OpenJSSE,
# которых в обычном приложении нет.
-dontwarn okhttp3.internal.platform.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# EncryptedSharedPreferences тянет Tink, а тот скомпилирован со ссылками на аннотации
# errorprone, которых нет в рантайме. Без этого правила R8 у ПОТРЕБИТЕЛЯ падает на
# «Missing class com.google.errorprone.annotations.*» — то есть наша зависимость ломает
# release-сборку чужого приложения. Проверено: demo:assembleRelease.
-dontwarn com.google.errorprone.annotations.**
