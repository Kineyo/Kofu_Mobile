package com.kofu.assistant

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.ui.ChatScreen

import com.kofu.assistant.ui.theme.KofuTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // NDK Llama.cpp will be initialized later natively

        // val prefs = getSharedPreferences("kofu_prefs", MODE_PRIVATE)

        setContent {
            var isDarkTheme by rememberSaveable { mutableStateOf(true) }
            val repository = LlamaRepository

            KofuTheme(isDarkTheme = isDarkTheme) {
                ChatScreen(
                    repository = repository,
                    isDarkTheme = isDarkTheme,
                    onToggleTheme = { isDarkTheme = !isDarkTheme }
                )
            }
        }
    }
}
