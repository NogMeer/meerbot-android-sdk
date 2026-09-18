package ru.meerbot.sdk.network

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Build
import androidx.core.content.pm.PackageInfoCompat
import java.util.Locale
import java.util.TimeZone

/**
 * Device-контекст, уходящий в тело `register` вложенным объектом `device`.
 *
 * Собирается ЛЕНИВО на каждое рукопожатие (не кэшируется в [ApiClient]) — локаль и часовой
 * пояс пользователь может сменить, не перезапуская процесс. `appVersion`/`appBuild` хост может
 * переопределить через [MeerBotConfiguration]; иначе они читаются из `PackageInfo`.
 *
 * `androidx.core:core` в зависимостях модуля не объявлена явно — приезжает транзитивом через
 * `activity-compose` (уже используется SDK для Compose-экрана), поэтому `PackageInfoCompat`
 * доступен без новой строки в `build.gradle.kts`.
 */
internal object DeviceContext {

    private const val MAX_FIELD_LENGTH = 64

    /**
     * @return непустые поля device-контекста (пустая карта — ничего добавлять в тело не нужно).
     *   Сбой чтения `PackageInfo` (пакет не найден и т.п.) не прерывает сборку — соответствующие
     *   поля просто отсутствуют, рукопожатие не должно падать из-за диагностики.
     */
    fun collect(context: Context, configuration: MeerBotConfiguration): Map<String, String> {
        val packageInfo = runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0)
        }.getOrNull()

        val fields = linkedMapOf(
            "appVersion" to (configuration.appVersion ?: packageInfo?.versionName),
            "appBuild" to (configuration.appBuild ?: packageInfo?.let(::versionCode)),
            "model" to deviceModel(),
            "osVersion" to Build.VERSION.RELEASE,
            "locale" to runCatching { Locale.getDefault().toLanguageTag() }.getOrNull(),
            "timezone" to runCatching { TimeZone.getDefault().id }.getOrNull(),
        )

        val result = LinkedHashMap<String, String>(fields.size)
        for ((key, value) in fields) {
            val trimmed = value?.trim().orEmpty()
            if (trimmed.isNotEmpty()) result[key] = trimmed.take(MAX_FIELD_LENGTH)
        }
        return result
    }

    private fun versionCode(info: PackageInfo): String =
        PackageInfoCompat.getLongVersionCode(info).toString()

    /** `"Manufacturer Model"`, без дублирования, если модель уже несёт имя производителя. */
    private fun deviceModel(): String? {
        val manufacturer = Build.MANUFACTURER?.trim().orEmpty()
        val model = Build.MODEL?.trim().orEmpty()
        val combined = when {
            model.isEmpty() -> manufacturer
            manufacturer.isEmpty() -> model
            model.startsWith(manufacturer, ignoreCase = true) -> model
            else -> "$manufacturer $model"
        }
        return combined.ifEmpty { null }
    }
}
