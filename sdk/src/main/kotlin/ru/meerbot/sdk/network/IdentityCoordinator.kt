package ru.meerbot.sdk.network

import androidx.annotation.MainThread

/**
 * Применяет `MeerBot.identify` к клиенту и ленте. Вынесен из синглтона, чтобы решение
 * проверялось без `Context`.
 *
 * @param installationId к нему привязан хеш `sub` (см. [IdentitySubject.hash]).
 * @param subjects хеш последнего применённого `sub`; в SDK — в prefs, поэтому первый
 *   `identify` после перезапуска с тем же человеком ленту не очищает.
 * @param resetFeed очистить ленту и перезапустить открытый экран.
 */
internal class IdentityCoordinator(
    private val client: ApiClient,
    private val installationId: String,
    private val subjects: SubjectHashStore,
    private val resetFeed: () -> Unit,
) {

    @MainThread
    fun apply(token: String?): IdentityChange {
        val subject = token?.let { IdentitySubject.of(it) }
        val newHash = token?.let { IdentitySubject.hash(installationId, subject ?: it) }
        val change = IdentitySubject.change(subjects.hash, newHash, newSubjectReadable = subject != null)
        when {
            token == null -> client.logout()
            change == IdentityChange.Refresh -> client.refreshIdentityToken(token)
            else -> client.switchIdentity(token)
        }
        // Хеш — после клиента: флаг выхода обязан лечь раньше, чем SDK «забудет» прежнего.
        subjects.hash = newHash
        if (change != IdentityChange.Refresh) resetFeed()
        return change
    }
}
