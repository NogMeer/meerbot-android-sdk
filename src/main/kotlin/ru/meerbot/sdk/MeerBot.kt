package ru.meerbot.sdk

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import java.util.UUID

/**
 * MeerBot Android SDK — Public API entry point.
 *
 * Phase 5 scaffolding. Полная имплементация — Phase 5.c (ui/ChatScreen.kt с Jetpack Compose).
 * Public API контракт идентичен iOS Swift + RN (см. docs/mobile-sdk/api-reference.md).
 */
object MeerBot {

    const val VERSION = "0.1.0-alpha"
    const val API_BASE_URL = "https://meerbot.ru"

    private const val PREF_NAME = "meerbot_sdk"
    private const val KEY_VISITOR_UUID = "visitor_uuid"

    private var apiKey: String? = null
    private var visitorUuid: String? = null
    private var jwt: String? = null
    private var prefs: SharedPreferences? = null

    /**
     * Configure SDK с published mobile app credentials.
     * @param apiKey pk_live_* из /cabinet/integrations/mobile в кабинете владельца app.
     * @param userId опциональный external user id (HMAC-signed на backend) для identification.
     */
    fun configure(context: Context, apiKey: String, userId: String? = null) {
        this.apiKey = apiKey
        this.prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        this.visitorUuid = getOrCreateVisitorUuid()
        // TODO Phase 5.c: register device через POST /api/v1/mobile/register
        // TODO Phase 5.c: bootstrap JWT + start Play Integrity verification
    }

    /**
     * Composable для встраивания ChatScreen в Jetpack Compose hierarchy.
     */
    @Composable
    fun ChatScreen(modifier: Modifier = Modifier) {
        Column(modifier = modifier.padding(16.dp)) {
            Text("MeerBot Chat")
            Text("Phase 5.c UI implementation pending")
        }
    }

    /**
     * Register для FCM push notifications. Caller должен сначала получить token из
     * FirebaseMessaging.getInstance().token и передать сюда.
     */
    fun setPushToken(token: String) {
        // TODO Phase 5.c: POST /api/v1/mobile/register с deviceToken
        android.util.Log.d("MeerBot", "FCM token registered: ${token.take(12)}...")
    }

    /**
     * Handle incoming FCM message (вызывается из FirebaseMessagingService.onMessageReceived).
     */
    fun handlePush(data: Map<String, String>): Boolean {
        val conversationId = data["conversationId"]?.toLongOrNull() ?: return false
        // TODO Phase 5.c: deep link в ChatScreen с conversationId
        android.util.Log.d("MeerBot", "push for conversation $conversationId")
        return true
    }

    /**
     * Reset SDK state — для GDPR Art. 17 invocation на client side.
     */
    fun reset() {
        this.apiKey = null
        this.visitorUuid = null
        this.jwt = null
        prefs?.edit()?.clear()?.apply()
        // TODO Phase 5.c: secure delete JWT из EncryptedSharedPreferences
    }

    private fun getOrCreateVisitorUuid(): String {
        val existing = prefs?.getString(KEY_VISITOR_UUID, null)
        if (existing != null) return existing
        val new = UUID.randomUUID().toString()
        prefs?.edit()?.putString(KEY_VISITOR_UUID, new)?.apply()
        return new
    }
}
