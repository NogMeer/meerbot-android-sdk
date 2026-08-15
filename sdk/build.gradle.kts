// MeerBot Android SDK — библиотека `ru.meerbot:sdk`.
//
// Публичный контракт совпадает с iOS Swift SDK (docs/mobile-sdk/api-reference.md).
//
// Чего здесь СОЗНАТЕЛЬНО нет:
//   • firebase-messaging — SDK принимает уже готовый FCM-токен строкой (`MeerBot.setPushToken`),
//     а не тянет Firebase в каждое приложение-потребителя вместе с google-services.
//   • play:integrity — серверная аттестация (`/api/v1/mobile/attestation`) сегодня заглушка,
//     класть клиентскую половину «на веру» нельзя.

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("maven-publish")
}

val sdkVersion: String = providers.gradleProperty("SDK_VERSION").get()

android {
    namespace = "ru.meerbot.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
        buildConfigField("String", "SDK_VERSION", "\"$sdkVersion\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests {
            // Тесты сетевого слоя гоняют реальный OkHttp против MockWebServer, но трогают
            // android.util.Log — без этого он бросает "not mocked".
            isReturnDefaultValues = true
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Корутины объявлены явно: раньше проект держался на транзитиве от lifecycle —
    // обновление lifecycle молча меняло бы версию корутин под сетевым слоем.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // visitorUuid хранится в зашифрованных prefs. Версия стабильная: тащить alpha-крипту
    // в чужое приложение нельзя.
    implementation("androidx.security:security-crypto:1.1.0")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.7.3")
    // В unit-тестах android.jar отдаёт org.json заглушками (все методы null/0), поэтому
    // разбор ответов проверялся бы вхолостую. Настоящая реализация — из этой зависимости.
    testImplementation("org.json:json:20240303")

    // Инструментальные тесты экрана: гоняются на эмуляторе (`connectedDebugAndroidTest`).
    // Именно они держат связку UI ↔ состояние, которой в каркасе не было вовсе.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ru.meerbot"
            artifactId = "sdk"
            version = sdkVersion

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("MeerBot Android SDK")
                description.set("Чат поддержки MeerBot для Android: Compose-экран и клиент платформы")
                url.set("https://meerbot.ru")
                licenses {
                    license {
                        name.set("Proprietary")
                        url.set("https://meerbot.ru")
                    }
                }
            }
        }
    }
    repositories {
        // Публикация по умолчанию — в локальный каталог сборки: артефакт можно проверить
        // и подписать до заливки. Внешний Maven подключается переменными окружения
        // (см. scripts/release-android-sdk.sh).
        maven {
            name = "buildDir"
            url = uri(layout.buildDirectory.dir("repo"))
        }
    }
}
