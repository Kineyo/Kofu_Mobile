package com.kofu.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.ui.ChatScreen
import com.kofu.assistant.ui.theme.KofuTheme
import com.kofu.assistant.ui.theme.KofuThemeMode

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "kofu_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedThemeName = prefs.getString(KEY_THEME_MODE, KofuThemeMode.SYSTEM.name)
        val initialThemeMode = runCatching {
            KofuThemeMode.valueOf(savedThemeName ?: KofuThemeMode.SYSTEM.name)
        }.getOrDefault(KofuThemeMode.SYSTEM)

        setContent {
            var themeMode by rememberSaveable { mutableStateOf(initialThemeMode) }
            val repository = LlamaRepository

            KofuTheme(themeMode = themeMode) {
                ChatScreen(
                    repository = repository,
                    themeMode = themeMode,
                    onChangeThemeMode = { newMode ->
                        themeMode = newMode
                        prefs.edit().putString(KEY_THEME_MODE, newMode.name).apply()
                    }
                )
            }
        }
    }
}
