// Демо-приложение SDK: аналог mobile-sdk-ios/Example.
//
// Ключи в репозиторий не кладём — их вводят в самом приложении и они остаются в prefs
// устройства. Это же приложение служит стендом для сквозного прогона против прода.

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ru.meerbot.demo"
    compileSdk = 34

    defaultConfig {
        applicationId = "ru.meerbot.demo"
        // Демо не тиражируется, поэтому minSdk выше библиотечного (24) — ради DayNight-темы
        // без дублирования ресурсов.
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = providers.gradleProperty("SDK_VERSION").get()
    }

    buildFeatures {
        compose = true
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

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            // Демо релизится только на эмулятор: подпись отладочная, чтобы урезанный R8
            // сборкой SDK можно было реально запустить, а не только скомпилировать.
            // Consumer-правила проверяются именно так.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(project(":sdk"))

    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    debugImplementation(composeBom)
    debugImplementation("androidx.compose.ui:ui-tooling")
}
