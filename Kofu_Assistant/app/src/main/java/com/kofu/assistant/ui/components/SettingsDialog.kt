package com.kofu.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.kofu.assistant.repository.BackendMode
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.ui.theme.KofuDesignSystem
import com.kofu.assistant.ui.theme.KofuFontScale
import com.kofu.assistant.ui.theme.KofuThemeMode
import com.kofu.assistant.ui.theme.LocalKofuColors

@Composable
fun SettingsDialog(
    isOpen: Boolean,
    repository: LlamaRepository,
    themeMode: KofuThemeMode,
    onChangeThemeMode: (KofuThemeMode) -> Unit,
    fontScale: KofuFontScale,
    onChangeFontScale: (KofuFontScale) -> Unit,
    onOpenTerms: () -> Unit,
    onClearChat: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!isOpen) return

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val colors = LocalKofuColors.current
    val scrollState = rememberScrollState()

    var testConnectionInProgress by remember { mutableStateOf(false) }
    var connectionFeedback by remember { mutableStateOf<String?>(null) }

    fun triggerTestConnection() {
        testConnectionInProgress = true
        connectionFeedback = null
        repository.probeOllama(scope) { success ->
            testConnectionInProgress = false
            connectionFeedback = if (success) {
                "🟢 Conectado: ${repository.ollamaAvailableModels.size} modelo(s) detectado(s)"
            } else {
                "🔴 Sin conexión con el servidor Ollama"
            }
            repository.savePreferences(context)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = KofuDesignSystem.DialogShape,
            color = colors.surface,
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.88f)
                .clip(KofuDesignSystem.DialogShape)
                .border(1.dp, colors.divider, KofuDesignSystem.DialogShape)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // CABECERA FIJA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Surface(
                            color = colors.accentSoft,
                            shape = KofuDesignSystem.ButtonShape,
                            modifier = Modifier.size(36.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = Icons.Default.Settings,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        Column {
                            Text(
                                text = "Ajustes de Kofu",
                                fontWeight = FontWeight.Bold,
                                color = colors.onSurface,
                                fontSize = 17.sp
                            )
                            Text(
                                text = "Motor, inferencia y personalización",
                                color = colors.onSurfaceSecondary,
                                fontSize = 11.sp
                            )
                        }
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = colors.onSurfaceSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                // CONTENIDO DESPLAZABLE FLUIDO
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // SECCIÓN 1: MOTOR & INFERENCIA
                    SettingsSectionHeader(title = "⚙️ MOTOR DE INFERENCIA")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Modo de Inferencia",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = colors.onSurface
                            )

                            BackendMode.entries.forEach { mode ->
                                val isSelected = repository.backendMode == mode
                                Surface(
                                    color = if (isSelected) colors.accentSoft else colors.surface,
                                    shape = KofuDesignSystem.ButtonShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(KofuDesignSystem.ButtonShape)
                                        .border(
                                            width = if (isSelected) 1.5.dp else 1.dp,
                                            color = if (isSelected) colors.accent else colors.divider,
                                            shape = KofuDesignSystem.ButtonShape
                                        )
                                        .clickable {
                                            repository.backendMode = mode
                                            repository.savePreferences(context)
                                            if (mode == BackendMode.OLLAMA || mode == BackendMode.AUTO) {
                                                triggerTestConnection()
                                            }
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = mode.displayName,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = colors.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = colors.accent,
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Configuración del servidor Ollama
                            AnimatedVisibility(
                                visible = repository.backendMode != BackendMode.LOCAL_CPP,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Text(
                                        text = "Dirección del Servidor Ollama (Laptop / Red):",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurfaceSecondary
                                    )

                                    OutlinedTextField(
                                        value = repository.ollamaHost,
                                        onValueChange = {
                                            repository.ollamaHost = it
                                            repository.savePreferences(context)
                                        },
                                        singleLine = true,
                                        textStyle = TextStyle(
                                            fontFamily = FontFamily.Monospace,
                                            fontSize = 13.sp,
                                            color = colors.onSurface
                                        ),
                                        shape = KofuDesignSystem.InputShape,
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedContainerColor = colors.inputBackground,
                                            unfocusedContainerColor = colors.inputBackground,
                                            focusedBorderColor = colors.inputBorderFocused,
                                            unfocusedBorderColor = colors.inputBorder
                                        )
                                    )

                                    Button(
                                        onClick = { triggerTestConnection() },
                                        enabled = !testConnectionInProgress,
                                        shape = KofuDesignSystem.ButtonShape,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = colors.accentSoft,
                                            contentColor = colors.accent
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        if (testConnectionInProgress) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(14.dp),
                                                strokeWidth = 2.dp,
                                                color = colors.accent
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Comprobando...", fontSize = 12.sp)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(14.dp)
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Probar Conexión", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                        }
                                    }

                                    if (connectionFeedback != null) {
                                        Text(
                                            text = connectionFeedback!!,
                                            fontSize = 11.sp,
                                            color = if (repository.isOllamaConnected) colors.successAccent else colors.errorAccent
                                        )
                                    }

                                    // Selector de modelo Ollama
                                    if (repository.ollamaAvailableModels.isNotEmpty()) {
                                        Text(
                                            text = "Modelo en Ollama:",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = colors.onSurfaceSecondary
                                        )

                                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                            repository.ollamaAvailableModels.forEach { modelName ->
                                                val isPicked = repository.ollamaModel == modelName
                                                Surface(
                                                    color = if (isPicked) colors.accentSoft else colors.surface,
                                                    shape = KofuDesignSystem.ButtonShape,
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clip(KofuDesignSystem.ButtonShape)
                                                        .border(
                                                            1.dp,
                                                            if (isPicked) colors.accent else colors.divider,
                                                            KofuDesignSystem.ButtonShape
                                                        )
                                                        .clickable {
                                                            repository.ollamaModel = modelName
                                                            repository.currentModelName = "$modelName (Ollama)"
                                                            repository.savePreferences(context)
                                                        }
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 10.dp, vertical = 7.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = modelName,
                                                            fontSize = 12.sp,
                                                            fontWeight = if (isPicked) FontWeight.Bold else FontWeight.Normal,
                                                            color = colors.onSurface
                                                        )
                                                        if (isPicked) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = colors.accent,
                                                                modifier = Modifier.size(16.dp)
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECCIÓN 2: PARÁMETROS DE GENERACIÓN IA
                    SettingsSectionHeader(title = "🎛️ PARÁMETROS DE GENERACIÓN")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Temperatura
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Temperatura (Creatividad)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "%.2f".format(repository.temperature),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.accent
                                    )
                                }
                                Slider(
                                    value = repository.temperature,
                                    onValueChange = {
                                        repository.temperature = it
                                        repository.savePreferences(context)
                                    },
                                    valueRange = 0.0f..1.5f,
                                    steps = 14,
                                    colors = SliderDefaults.colors(
                                        thumbColor = colors.accent,
                                        activeTrackColor = colors.accent,
                                        inactiveTrackColor = colors.divider
                                    )
                                )
                            }

                            // Top-P
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Top-P (Nucleus Sampling)",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "%.2f".format(repository.topP),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.accent
                                    )
                                }
                                Slider(
                                    value = repository.topP,
                                    onValueChange = {
                                        repository.topP = it
                                        repository.savePreferences(context)
                                    },
                                    valueRange = 0.1f..1.0f,
                                    steps = 8,
                                    colors = SliderDefaults.colors(
                                        thumbColor = colors.accent,
                                        activeTrackColor = colors.accent,
                                        inactiveTrackColor = colors.divider
                                    )
                                )
                            }

                            // Penalización por repetición
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Penalización de Repetición",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "%.2f".format(repository.repeatPenalty),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.accent
                                    )
                                }
                                Slider(
                                    value = repository.repeatPenalty,
                                    onValueChange = {
                                        repository.repeatPenalty = it
                                        repository.savePreferences(context)
                                    },
                                    valueRange = 1.0f..1.5f,
                                    steps = 9,
                                    colors = SliderDefaults.colors(
                                        thumbColor = colors.accent,
                                        activeTrackColor = colors.accent,
                                        inactiveTrackColor = colors.divider
                                    )
                                )
                            }

                            // Tokens Máximos
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Límite de Tokens de Salida:",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    listOf(512, 1024, 2048, 4096).forEach { tokLimit ->
                                        val isSelected = repository.maxTokens == tokLimit
                                        Surface(
                                            color = if (isSelected) colors.accent else colors.surface,
                                            shape = KofuDesignSystem.ButtonShape,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(KofuDesignSystem.ButtonShape)
                                                .border(
                                                    1.dp,
                                                    if (isSelected) colors.accent else colors.divider,
                                                    KofuDesignSystem.ButtonShape
                                                )
                                                .clickable {
                                                    repository.maxTokens = tokLimit
                                                    repository.savePreferences(context)
                                                }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = "$tokLimit",
                                                    fontSize = 11.sp,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = if (isSelected) colors.sendButtonText else colors.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // Prompt del Sistema
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text(
                                    text = "Instrucción de Personalidad (System Prompt):",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = colors.onSurface
                                )

                                OutlinedTextField(
                                    value = repository.systemPrompt,
                                    onValueChange = {
                                        repository.systemPrompt = it
                                        repository.savePreferences(context)
                                    },
                                    maxLines = 4,
                                    textStyle = TextStyle(fontSize = 12.sp, color = colors.onSurface),
                                    shape = KofuDesignSystem.InputShape,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = colors.inputBackground,
                                        unfocusedContainerColor = colors.inputBackground,
                                        focusedBorderColor = colors.inputBorderFocused,
                                        unfocusedBorderColor = colors.inputBorder
                                    )
                                )

                                // Presets rápidos
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(
                                        "Asistente" to "Eres Kofu, un asistente de IA local útil, atento, privado y respetuoso. Responde de forma clara, precisa y concisa en español.",
                                        "Programador" to "Eres Kofu, un arquitecto de software senior experto en Kotlin, Python y C++. Explica con código limpio, bien tipado y estructurado.",
                                        "Ultra Conciso" to "Eres Kofu. Responde de manera extremadamente directa y sintética, sin introducciones innecesarias."
                                    ).forEach { (label, promptText) ->
                                        Surface(
                                            color = colors.surface,
                                            shape = KofuDesignSystem.ButtonShape,
                                            modifier = Modifier
                                                .clip(KofuDesignSystem.ButtonShape)
                                                .border(1.dp, colors.divider, KofuDesignSystem.ButtonShape)
                                                .clickable {
                                                    repository.systemPrompt = promptText
                                                    repository.savePreferences(context)
                                                }
                                        ) {
                                            Text(
                                                text = label,
                                                fontSize = 10.sp,
                                                color = colors.accent,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECCIÓN 3: MODO DE DEPURACIÓN (TELEMETRÍA EN VIVO)
                    SettingsSectionHeader(title = "🐞 MODO DE DEPURACIÓN")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "Activar HUD de Telemetría",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "Muestra t/s en vivo, TTFT de latencia, tokens y monitor de prompts.",
                                    fontSize = 11.sp,
                                    color = colors.onSurfaceSecondary
                                )
                            }

                            Switch(
                                checked = repository.isDebugMode,
                                onCheckedChange = {
                                    repository.isDebugMode = it
                                    repository.savePreferences(context)
                                },
                                colors = SwitchDefaults.colors(
                                    checkedThumbColor = colors.accent,
                                    checkedTrackColor = colors.accentSoft,
                                    uncheckedThumbColor = colors.onSurfaceSecondary,
                                    uncheckedTrackColor = colors.surface
                                )
                            )
                        }
                    }

                    // SECCIÓN 4: APARIENCIA Y TIPOGRAFÍA
                    SettingsSectionHeader(title = "🎨 APARIENCIA & TEMA")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Text(
                                text = "Tema de la Interfaz",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                KofuThemeMode.entries.forEach { mode ->
                                    val isSelected = mode == themeMode
                                    Surface(
                                        color = if (isSelected) colors.accentSoft else colors.surface,
                                        shape = KofuDesignSystem.ButtonShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(KofuDesignSystem.ButtonShape)
                                            .border(
                                                width = if (isSelected) 1.5.dp else 1.dp,
                                                color = if (isSelected) colors.accent else colors.divider,
                                                shape = KofuDesignSystem.ButtonShape
                                            )
                                            .clickable { onChangeThemeMode(mode) }
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        ) {
                                            Text(text = mode.icon, fontSize = 14.sp)
                                            Spacer(modifier = Modifier.height(2.dp))
                                            Text(
                                                text = mode.label,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = colors.onSurface
                                            )
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "Tamaño de Fuente",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                color = colors.onSurface
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                KofuFontScale.entries.forEach { scale ->
                                    val isSelected = scale == fontScale
                                    Surface(
                                        color = if (isSelected) colors.accent else colors.surface,
                                        shape = KofuDesignSystem.ButtonShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(KofuDesignSystem.ButtonShape)
                                            .border(
                                                1.dp,
                                                if (isSelected) colors.accent else colors.divider,
                                                KofuDesignSystem.ButtonShape
                                            )
                                            .clickable { onChangeFontScale(scale) }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(vertical = 8.dp)
                                        ) {
                                            Text(
                                                text = scale.label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = if (isSelected) colors.sendButtonText else colors.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECCIÓN 5: PRIVACIDAD, LEGAL Y ACCIONES
                    SettingsSectionHeader(title = "📜 PRIVACIDAD & ACCIONES")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    onDismiss()
                                    onOpenTerms()
                                },
                                shape = KofuDesignSystem.ButtonShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Info,
                                    contentDescription = null,
                                    tint = colors.accent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Ver Términos y Condiciones / Licencia Gemma",
                                    fontSize = 12.sp,
                                    color = colors.onSurface
                                )
                            }

                            OutlinedButton(
                                onClick = {
                                    onClearChat()
                                    onDismiss()
                                },
                                shape = KofuDesignSystem.ButtonShape,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Delete,
                                    contentDescription = null,
                                    tint = colors.errorAccent,
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Limpiar Historial de Conversación",
                                    fontSize = 12.sp,
                                    color = colors.errorAccent
                                )
                            }
                        }
                    }
                }

                // PIE FIJO CON BOTÓN DE GUARDAR Y CERRAR
                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onDismiss,
                    shape = KofuDesignSystem.ButtonShape,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = colors.accent,
                        contentColor = colors.sendButtonText
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Guardar y Cerrar",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun SettingsSectionHeader(title: String) {
    val colors = LocalKofuColors.current
    Text(
        text = title,
        fontSize = 11.sp,
        fontWeight = FontWeight.ExtraBold,
        letterSpacing = 0.8.sp,
        color = colors.accent
    )
}
