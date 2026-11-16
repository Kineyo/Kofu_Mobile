package com.kofu.assistant

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.kofu.assistant.data.KofuEditionProfile
import com.kofu.assistant.data.rag.KofuRagDatabase
import com.kofu.assistant.repository.BackendMode
import com.kofu.assistant.repository.KofuNativeEngine
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.ui.ChatScreen
import com.kofu.assistant.ui.theme.KofuFontScale
import com.kofu.assistant.ui.theme.KofuTheme
import com.kofu.assistant.ui.theme.KofuThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    companion object {
        private const val PREFS_NAME = "kofu_prefs"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_FONT_SCALE = "font_scale"
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* La notificación se mostrará si el permiso fue concedido */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        val savedThemeName = prefs.getString(KEY_THEME_MODE, KofuThemeMode.DARK.name)
        val initialThemeMode = runCatching {
            KofuThemeMode.valueOf(savedThemeName ?: KofuThemeMode.DARK.name)
        }.getOrDefault(KofuThemeMode.DARK)

        val savedFontScaleName = prefs.getString(KEY_FONT_SCALE, KofuFontScale.STANDARD.name)
        val initialFontScale = runCatching {
            KofuFontScale.valueOf(savedFontScaleName ?: KofuFontScale.STANDARD.name)
        }.getOrDefault(KofuFontScale.STANDARD)

        val am = getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
        if (am != null) {
            val memInfo = ActivityManager.MemoryInfo()
            am.getMemoryInfo(memInfo)
            KofuEditionProfile.configureForDeviceRam(memInfo.totalMem)
        }

        val repository = LlamaRepository
        repository.loadPreferences(this)

        // Pre-indexar base RAG offline SQLite FTS4 + BM25 y micro-embedder GGUF en segundo plano
        lifecycleScope.launch(Dispatchers.IO) {
            KofuRagDatabase.getInstance(applicationContext)
            repository.ensureEmbedderReady(applicationContext)
        }

        // Operación 100% offline por defecto; solo sondear Ollama si el modo elegido lo requiere
        if (repository.backendMode != BackendMode.LOCAL_CPP) {
            repository.probeOllama(lifecycleScope)
        }

        // Cargar modelo local disponible o descargar el modelo oficial si no existe
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

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE) {
            KofuRagDatabase.getInstanceOrNull()?.onLowMemoryTrim()
            if (KofuNativeEngine.isNativeReady()) {
                KofuNativeEngine.nativeTrimMemoryLevel(level)
            }
        }
    }
}
