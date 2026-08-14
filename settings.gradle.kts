// Корень Gradle-проекта Android SDK.
//
// Проект намеренно автономен от монорепо: `mobile-sdk-android` уезжает потребителю как
// Maven-артефакт `ru.meerbot:sdk`, а не как папка внутри agentbot-platform.

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "meerbot-android-sdk"

include(":sdk")
include(":demo")
