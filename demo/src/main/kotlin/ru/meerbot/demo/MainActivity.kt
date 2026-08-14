package ru.meerbot.demo

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.isSystemInDarkTheme
import ru.meerbot.sdk.MeerBot
import ru.meerbot.sdk.network.MeerBotConfiguration

/**
 * Демо-приложение SDK.
 *
 * Экран настроек → `MeerBot.configure(...)` → экран чата. Введённые ключи остаются в prefs
 * устройства, в репозиторий они не попадают.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme(),
            ) {
                Surface(modifier = Modifier.fillMaxSize()) { DemoApp() }
            }
        }
    }
}

@Composable
private fun DemoApp() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("meerbot_demo", Context.MODE_PRIVATE) }

    var apiKey by rememberSaveable { mutableStateOf(prefs.getString("apiKey", "").orEmpty()) }
    var pushApiKey by rememberSaveable { mutableStateOf(prefs.getString("pushApiKey", "").orEmpty()) }
    var origin by rememberSaveable { mutableStateOf(prefs.getString("origin", "https://meerbot.ru").orEmpty()) }
    var baseUrl by rememberSaveable {
        mutableStateOf(prefs.getString("baseUrl", MeerBotConfiguration.DEFAULT_BASE_URL).orEmpty())
    }
    var chatOpen by rememberSaveable { mutableStateOf(false) }

    if (chatOpen) {
        MeerBot.ChatScreen(onClose = { chatOpen = false })
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("MeerBot SDK ${MeerBot.VERSION}", style = MaterialTheme.typography.titleLarge)
        Text(
            "Ключ виджета — из кабинета: Бот → Каналы → headless-виджет. " +
                "Origin обязан быть в списке разрешённых доменов этого ключа.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("apiKey (pk_live_… виджета)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = pushApiKey,
            onValueChange = { pushApiKey = it },
            label = { Text("pushApiKey (необязательно)") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = origin,
            onValueChange = { origin = it },
            label = { Text("Origin") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = baseUrl,
            onValueChange = { baseUrl = it },
            label = { Text("Base URL") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = {
                prefs.edit()
                    .putString("apiKey", apiKey.trim())
                    .putString("pushApiKey", pushApiKey.trim())
                    .putString("origin", origin.trim())
                    .putString("baseUrl", baseUrl.trim())
                    .apply()
                MeerBot.configure(
                    context = context,
                    apiKey = apiKey.trim(),
                    pushApiKey = pushApiKey.trim().takeIf { it.isNotEmpty() },
                    origin = origin.trim().takeIf { it.isNotEmpty() },
                    baseUrl = baseUrl.trim(),
                )
                chatOpen = true
            },
            enabled = apiKey.isNotBlank(),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Открыть чат")
        }

        Button(
            onClick = {
                MeerBot.reset()
                prefs.edit().clear().apply()
                apiKey = ""
                pushApiKey = ""
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Сбросить состояние SDK")
        }
    }
}
