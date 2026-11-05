package com.kofu.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.ui.ChatScreen
import com.kofu.assistant.ui.theme.KofuFontScale
import com.kofu.assistant.ui.theme.KofuTheme
import com.kofu.assistant.ui.theme.KofuThemeMode

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "kofu_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedThemeName = prefs.getString(KEY_THEME_MODE, KofuThemeMode.DARK.name)
        val initialThemeMode = runCatching {
            KofuThemeMode.valueOf(savedThemeName ?: KofuThemeMode.DARK.name)
        }.getOrDefault(KofuThemeMode.DARK)

        val savedFontScaleName = prefs.getString(KEY_FONT_SCALE, KofuFontScale.STANDARD.name)
        val initialFontScale = runCatching {
            KofuFontScale.valueOf(savedFontScaleName ?: KofuFontScale.STANDARD.name)
        }.getOrDefault(KofuFontScale.STANDARD)

        val repository = LlamaRepository
        repository.loadPreferences(this)

        // Detección automática del servidor Ollama (laptop por ADB reverse o red local)
        repository.probeOllama(lifecycleScope)

        // Descarga automática por defecto del modelo desde Hugging Face
        repository.startAutoDownloadIfNeeded(this, lifecycleScope)

        setContent {
            var themeMode by rememberSaveable { mutableStateOf(initialThemeMode) }
            var fontScale by rememberSaveable { mutableStateOf(initialFontScale) }

            KofuTheme(themeMode = themeMode, fontScale = fontScale) {
                ChatScreen(
                    repository = repository,
                    themeMode = themeMode,
                    onChangeThemeMode = { newMode ->
                        themeMode = newMode
                        prefs.edit().putString(KEY_THEME_MODE, newMode.name).apply()
                    },
                    fontScale = fontScale,
                    onChangeFontScale = { newScale ->
                        fontScale = newScale
                        prefs.edit().putString(KEY_FONT_SCALE, newScale.name).apply()
                    }
                )
            }
        }
    }
}
