package com.kofu.assistant.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
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
import com.kofu.assistant.data.AppUpdateManager
import com.kofu.assistant.data.UpdateCheckResult
import com.kofu.assistant.repository.BackendMode
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.ui.theme.KofuDesignSystem
import com.kofu.assistant.ui.theme.KofuFontScale
import com.kofu.assistant.ui.theme.KofuThemeMode
import com.kofu.assistant.ui.theme.LocalKofuColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                "Conectado: ${repository.ollamaAvailableModels.size} modelo(s) en servidor"
            } else {
                "Sin respuesta del servidor Ollama"
            }
            repository.savePreferences(context)
        }
    }

    var updateResult by remember { mutableStateOf<UpdateCheckResult>(UpdateCheckResult.Idle) }

    fun triggerCheckUpdate() {
        updateResult = UpdateCheckResult.Loading
        scope.launch {
            val res = AppUpdateManager.checkLatestRelease()
            withContext(Dispatchers.Main) {
                updateResult = res.fold(
                    onSuccess = { release ->
                        if (release.isNewer) {
                            UpdateCheckResult.UpdateAvailable(release)
                        } else {
                            UpdateCheckResult.UpToDate(AppUpdateManager.CURRENT_VERSION_NAME, release.tagName)
                        }
                    },
                    onFailure = { err ->
                        UpdateCheckResult.Error(err.localizedMessage ?: "Error consultando GitHub")
                    }
                )
            }
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
                    .padding(14.dp)
            ) {
                // CABECERA FORMAL Y COMPACTA
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column {
                        Text(
                            text = "CONFIGURACIÓN",
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurface,
                            fontSize = 14.sp,
                            letterSpacing = 1.sp
                        )
                        Text(
                            text = "Parámetros de inferencia y motor",
                            color = colors.onSurfaceSecondary,
                            fontSize = 11.sp
                        )
                    }

                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = colors.onSurfaceSecondary,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

                // CONTENIDO DESPLAZABLE COMPACTO
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // SECCIÓN 1: MOTOR DE INFERENCIA
                    FormalSectionTitle(title = "MOTOR DE INFERENCIA")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            BackendMode.entries.forEach { mode ->
                                val isSelected = repository.backendMode == mode
                                Surface(
                                    color = if (isSelected) colors.accentSoft else colors.surface,
                                    shape = KofuDesignSystem.ButtonShape,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(KofuDesignSystem.ButtonShape)
                                        .border(
                                            width = 1.dp,
                                            color = if (isSelected) colors.onSurfaceSecondary else colors.divider,
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
                                            .padding(horizontal = 9.dp, vertical = 7.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.SpaceBetween
                                    ) {
                                        Text(
                                            text = mode.displayName,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = colors.onSurface
                                        )
                                        if (isSelected) {
                                            Icon(
                                                imageVector = Icons.Default.Check,
                                                contentDescription = null,
                                                tint = colors.onSurface,
                                                modifier = Modifier.size(15.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // Host Ollama
                            AnimatedVisibility(
                                visible = repository.backendMode != BackendMode.LOCAL_CPP,
                                enter = fadeIn() + expandVertically(),
                                exit = fadeOut() + shrinkVertically()
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(
                                        text = "HOST OLLAMA",
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
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
                                            fontSize = 12.sp,
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
                                            containerColor = colors.surface,
                                            contentColor = colors.onSurface
                                        ),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .border(1.dp, colors.divider, KofuDesignSystem.ButtonShape)
                                    ) {
                                        if (testConnectionInProgress) {
                                            CircularProgressIndicator(
                                                modifier = Modifier.size(12.dp),
                                                strokeWidth = 2.dp,
                                                color = colors.onSurface
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Comprobando...", fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                                        } else {
                                            Icon(
                                                imageVector = Icons.Default.Refresh,
                                                contentDescription = null,
                                                modifier = Modifier.size(13.dp),
                                                tint = colors.onSurfaceSecondary
                                            )
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Text("Probar Conexión", fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                                        }
                                    }

                                    if (connectionFeedback != null) {
                                        Text(
                                            text = connectionFeedback!!,
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            color = if (repository.isOllamaConnected) colors.successAccent else colors.errorAccent
                                        )
                                    }

                                    if (repository.ollamaAvailableModels.isNotEmpty()) {
                                        Text(
                                            text = "MODELO ACTIVO",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace,
                                            color = colors.onSurfaceSecondary
                                        )

                                        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
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
                                                            if (isPicked) colors.onSurfaceSecondary else colors.divider,
                                                            KofuDesignSystem.ButtonShape
                                                        )
                                                        .clickable {
                                                            repository.ollamaModel = modelName
                                                            repository.currentModelName = modelName
                                                            repository.savePreferences(context)
                                                        }
                                                ) {
                                                    Row(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .padding(horizontal = 9.dp, vertical = 6.dp),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Text(
                                                            text = modelName,
                                                            fontSize = 11.sp,
                                                            fontFamily = FontFamily.Monospace,
                                                            fontWeight = if (isPicked) FontWeight.Bold else FontWeight.Normal,
                                                            color = colors.onSurface
                                                        )
                                                        if (isPicked) {
                                                            Icon(
                                                                imageVector = Icons.Default.Check,
                                                                contentDescription = null,
                                                                tint = colors.onSurface,
                                                                modifier = Modifier.size(14.dp)
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

                    // SECCIÓN 2: PARÁMETROS IA
                    FormalSectionTitle(title = "PARÁMETROS DE GENERACIÓN")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Temperatura
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "Temperatura",
                                        fontSize = 11.5.sp,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "%.2f".format(repository.temperature),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
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
                                        thumbColor = colors.onSurface,
                                        activeTrackColor = colors.onSurfaceSecondary,
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
                                        text = "Top-P Sampling",
                                        fontSize = 11.5.sp,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "%.2f".format(repository.topP),
                                        fontSize = 11.sp,
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
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
                                        thumbColor = colors.onSurface,
                                        activeTrackColor = colors.onSurfaceSecondary,
                                        inactiveTrackColor = colors.divider
                                    )
                                )
                            }

                            // Tokens Máximos
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "LÍMITE DE TOKENS",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurfaceSecondary
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    listOf(512, 1024, 2048, 4096).forEach { tokLimit ->
                                        val isSelected = repository.maxTokens == tokLimit
                                        Surface(
                                            color = if (isSelected) colors.accentSoft else colors.surface,
                                            shape = KofuDesignSystem.ButtonShape,
                                            modifier = Modifier
                                                .weight(1f)
                                                .clip(KofuDesignSystem.ButtonShape)
                                                .border(
                                                    1.dp,
                                                    if (isSelected) colors.onSurfaceSecondary else colors.divider,
                                                    KofuDesignSystem.ButtonShape
                                                )
                                                .clickable {
                                                    repository.maxTokens = tokLimit
                                                    repository.savePreferences(context)
                                                }
                                        ) {
                                            Box(
                                                contentAlignment = Alignment.Center,
                                                modifier = Modifier.padding(vertical = 5.dp)
                                            ) {
                                                Text(
                                                    text = "$tokLimit",
                                                    fontSize = 10.5.sp,
                                                    fontFamily = FontFamily.Monospace,
                                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = colors.onSurface
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // System Prompt
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    text = "SYSTEM PROMPT",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace,
                                    color = colors.onSurfaceSecondary
                                )

                                OutlinedTextField(
                                    value = repository.systemPrompt,
                                    onValueChange = {
                                        repository.systemPrompt = it
                                        repository.savePreferences(context)
                                    },
                                    maxLines = 3,
                                    textStyle = TextStyle(fontSize = 11.5.sp, color = colors.onSurface),
                                    shape = KofuDesignSystem.InputShape,
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = OutlinedTextFieldDefaults.colors(
                                        focusedContainerColor = colors.inputBackground,
                                        unfocusedContainerColor = colors.inputBackground,
                                        focusedBorderColor = colors.inputBorderFocused,
                                        unfocusedBorderColor = colors.inputBorder
                                    )
                                )

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    listOf(
                                        "General" to "Eres Kofu, un asistente de IA formal, preciso y directo. Responde de forma técnica y concisa en español.",
                                        "Programador" to "Eres Kofu, un ingeniero de software senior. Responde exclusivamente con soluciones óptimas y código limpio.",
                                        "Conciso" to "Eres Kofu. Responde de manera concisa y directa, sin introducciones innecesarias."
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
                                                color = colors.onSurfaceSecondary,
                                                fontWeight = FontWeight.Medium,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECCIÓN 3: TELEMETRÍA & DEBUG
                    FormalSectionTitle(title = "TELEMETRÍA")

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
                                .padding(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "HUD de Telemetría",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = "Muestra velocidad (t/s), latencia TTFT y recuento de tokens.",
                                    fontSize = 10.5.sp,
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
                                    checkedThumbColor = colors.onSurface,
                                    checkedTrackColor = colors.accentSoft,
                                    uncheckedThumbColor = colors.onSurfaceMuted,
                                    uncheckedTrackColor = colors.surface
                                )
                            )
                        }
                    }

                    // SECCIÓN 4: APARIENCIA
                    FormalSectionTitle(title = "APARIENCIA")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "TEMA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurfaceSecondary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
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
                                                width = 1.dp,
                                                color = if (isSelected) colors.onSurfaceSecondary else colors.divider,
                                                shape = KofuDesignSystem.ButtonShape
                                            )
                                            .clickable { onChangeThemeMode(mode) }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(vertical = 7.dp)
                                        ) {
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
                                text = "ESCALA TIPOGRÁFICA",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace,
                                color = colors.onSurfaceSecondary
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                KofuFontScale.entries.forEach { scale ->
                                    val isSelected = scale == fontScale
                                    Surface(
                                        color = if (isSelected) colors.accentSoft else colors.surface,
                                        shape = KofuDesignSystem.ButtonShape,
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(KofuDesignSystem.ButtonShape)
                                            .border(
                                                1.dp,
                                                if (isSelected) colors.onSurfaceSecondary else colors.divider,
                                                KofuDesignSystem.ButtonShape
                                            )
                                            .clickable { onChangeFontScale(scale) }
                                    ) {
                                        Box(
                                            contentAlignment = Alignment.Center,
                                            modifier = Modifier.padding(vertical = 7.dp)
                                        ) {
                                            Text(
                                                text = scale.label,
                                                fontSize = 10.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                                color = colors.onSurface
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // SECCIÓN 5: ACTUALIZACIONES DE SOFTWARE
                    FormalSectionTitle(title = "ACTUALIZACIONES DE SOFTWARE")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Kofu Mobile v${AppUpdateManager.CURRENT_VERSION_NAME}",
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "GitHub Releases • Tag objetivo: 2.1",
                                        fontSize = 10.sp,
                                        color = colors.onSurfaceSecondary
                                    )
                                }

                                OutlinedButton(
                                    onClick = { triggerCheckUpdate() },
                                    enabled = updateResult !is UpdateCheckResult.Loading,
                                    shape = KofuDesignSystem.ButtonShape,
                                    modifier = Modifier.height(30.dp)
                                ) {
                                    if (updateResult is UpdateCheckResult.Loading) {
                                        CircularProgressIndicator(
                                            strokeWidth = 2.dp,
                                            color = colors.accent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Buscando...", fontSize = 10.sp)
                                    } else {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = colors.accent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Buscar", fontSize = 10.sp, color = colors.onSurface)
                                    }
                                }
                            }

                            when (val res = updateResult) {
                                is UpdateCheckResult.UpdateAvailable -> {
                                    Surface(
                                        color = colors.surfaceElevated,
                                        shape = KofuDesignSystem.ButtonShape,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, colors.successAccent),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(8.dp),
                                            verticalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Check,
                                                    contentDescription = null,
                                                    tint = colors.successAccent,
                                                    modifier = Modifier.size(14.dp)
                                                )
                                                Text(
                                                    text = "¡Nueva versión disponible: ${res.release.tagName}!",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = colors.successAccent
                                                )
                                            }
                                            if (res.release.title.isNotBlank()) {
                                                Text(
                                                    text = res.release.title,
                                                    fontSize = 10.5.sp,
                                                    color = colors.onSurfaceSecondary
                                                )
                                            }
                                            Button(
                                                onClick = {
                                                    val url = res.release.apkDownloadUrl ?: res.release.releasePageUrl
                                                    AppUpdateManager.openDownload(context, url)
                                                },
                                                shape = KofuDesignSystem.ButtonShape,
                                                colors = ButtonDefaults.buttonColors(
                                                    containerColor = colors.successAccent,
                                                    contentColor = colors.background
                                                ),
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(32.dp)
                                            ) {
                                                Text(
                                                    text = "Descargar APK (${res.release.tagName})",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                    }
                                }
                                is UpdateCheckResult.UpToDate -> {
                                    Text(
                                        text = "✓ Tienes la versión más reciente (${res.latestTag})",
                                        fontSize = 10.5.sp,
                                        color = colors.successAccent,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                                is UpdateCheckResult.Error -> {
                                    Text(
                                        text = "Aviso: ${res.message}",
                                        fontSize = 10.5.sp,
                                        color = colors.warningAccent
                                    )
                                }
                                UpdateCheckResult.Idle, UpdateCheckResult.Loading -> {}
                            }
                        }
                    }

                    // SECCIÓN 6: ACCIONES
                    FormalSectionTitle(title = "ACCIONES")

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
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
                                    tint = colors.onSurfaceSecondary,
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Términos de Uso y Licencia Gemma",
                                    fontSize = 11.5.sp,
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
                                    modifier = Modifier.size(15.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Limpiar Historial de Chat",
                                    fontSize = 11.5.sp,
                                    color = colors.errorAccent
                                )
                            }
                        }
                    }
                }

                // PIE FIJO CON BOTÓN
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = colors.divider, thickness = 1.dp)
                Spacer(modifier = Modifier.height(8.dp))

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
                        text = "Guardar",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
private fun FormalSectionTitle(title: String) {
    val colors = LocalKofuColors.current
    Text(
        text = title,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        fontFamily = FontFamily.Monospace,
        letterSpacing = 0.8.sp,
        color = colors.onSurfaceSecondary
    )
}
