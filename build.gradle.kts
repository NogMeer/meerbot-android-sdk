// MeerBot Android SDK — Phase 5 (Plan v2 ADR-003 triple-native).
// Native Android SDK с Jetpack Compose UI + native bridges
// (FCM, EncryptedSharedPreferences, Play Integrity, OkHttp CertificatePinner).
//
// Public API contract идентичен iOS Swift + RN.

plugins {
    id("com.android.library") version "8.5.0"
    id("org.jetbrains.kotlin.android") version "1.9.22"
    id("maven-publish")
}

android {
    namespace = "ru.meerbot.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        targetSdk = 34
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")

    // Networking
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")

    // Security
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // FCM
    implementation("com.google.firebase:firebase-messaging-ktx:23.4.0")

    // Play Integrity
    implementation("com.google.android.play:integrity:1.3.0")

    testImplementation("junit:junit:4.13.2")
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId = "ru.meerbot"
            artifactId = "sdk"
            version = "0.1.0-alpha"
            // afterEvaluate { from(components["release"]) } — добавится после Phase 5.b release variant
        }
    }
}
