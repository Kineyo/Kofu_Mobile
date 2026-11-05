package com.kofu.assistant.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kofu.assistant.repository.InferenceBackend
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.repository.ModelOption
import com.kofu.assistant.repository.TurnMessage
import com.kofu.assistant.ui.components.DebugHud
import com.kofu.assistant.ui.components.SettingsDialog
import com.kofu.assistant.ui.components.TermsDialog
import com.kofu.assistant.ui.theme.KofuDesignSystem
import com.kofu.assistant.ui.theme.KofuFontScale
import com.kofu.assistant.ui.theme.KofuThemeMode
import com.kofu.assistant.ui.theme.LocalKofuColors
import com.kofu.assistant.ui.theme.LocalKofuFontScale
import kotlinx.coroutines.launch
import java.util.UUID

enum class MessageSender { USER, KOFU }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val isPending: Boolean = false,
    val timestamp: String = ""
)

private val QUICK_PROMPTS = listOf(
    "✨ ¿Cómo puedes ayudarme hoy?",
    "💻 Explica qué es un puntero en C++ con un ejemplo",
    "🔒 ¿Por qué es más segura la IA local frente a la nube?",
    "🚀 Escribe una función recursiva para la serie Fibonacci en Python"
)

private const val WELCOME_MESSAGE =
    "¡Hola! Soy Kofu, tu asistente de IA de alto rendimiento con procesamiento 100% privado. ¿En qué te puedo ayudar hoy?"

@Composable
fun ChatScreen(
    repository: LlamaRepository,
    themeMode: KofuThemeMode = KofuThemeMode.DARK,
    onChangeThemeMode: (KofuThemeMode) -> Unit = {},
    fontScale: KofuFontScale = KofuFontScale.STANDARD,
    onChangeFontScale: (KofuFontScale) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val colors = LocalKofuColors.current
    val currentFontScale = LocalKofuFontScale.current

    val backgroundColor = colors.background
    val surfaceColor = colors.surface
    val onSurface = colors.onSurface
    val onSurfaceSecondary = colors.onSurfaceSecondary
    val userBubbleColor = colors.userBubble
    val userBubbleText = colors.userBubbleText
    val kofuTextColor = colors.kofuText
    val inputBg = colors.inputBackground
    val inputBorder = colors.inputBorder
    val inputBorderFocused = colors.inputBorderFocused
    val sendBtnColor = colors.sendButton
    val sendBtnDisabled = colors.sendButtonDisabled

    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    // Diálogos modales
    var showSettingsDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }

    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val serverReady = repository.isModelLoaded
    val activeBackend = repository.activeBackend
    val currentModelName = repository.currentModelName

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            downloadError = null
            repository.loadModel(context, it, scope)
        }
    }

    val messages = remember {
        mutableStateListOf(
            ChatMessage(
                sender = MessageSender.KOFU,
                text = WELCOME_MESSAGE
            )
        )
    }

    fun sendMessage(rawText: String) {
        val userMsgText = rawText.trim()
        if (userMsgText.isBlank() || isSending || !serverReady) return

        isSending = true
        inputText = ""

        messages.add(ChatMessage(sender = MessageSender.USER, text = userMsgText))

        val pendingId = UUID.randomUUID().toString()
        messages.add(
            ChatMessage(
                id = pendingId,
                sender = MessageSender.KOFU,
                text = "Kofu está procesando...",
                isPending = true
            )
        )

        scope.launch {
            try {
                var accumulatedText = ""
                var isFirstChunk = true

                val historyTurns = messages
                    .filter { !it.isPending && it.text.isNotBlank() }
                    .map { TurnMessage(if (it.sender == MessageSender.USER) "user" else "model", it.text) }

                repository.generateChatStream(historyTurns).collect { chunk ->
                    if (isFirstChunk) {
                        isFirstChunk = false
                        accumulatedText = ""
                    }
                    accumulatedText += chunk
                    val index = messages.indexOfFirst { it.id == pendingId }
                    if (index != -1) {
                        messages[index] = ChatMessage(
                            id = pendingId,
                            sender = MessageSender.KOFU,
                            text = accumulatedText,
                            isPending = false
                        )
                    }
                }

                if (accumulatedText.isBlank()) {
                    val index = messages.indexOfFirst { it.id == pendingId }
                    if (index != -1) {
                        messages[index] = ChatMessage(
                            id = pendingId,
                            sender = MessageSender.KOFU,
                            text = "No se recibió respuesta del modelo.",
                            isPending = false
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Error durante inferencia", e)
                val index = messages.indexOfFirst { it.id == pendingId }
                if (index != -1) {
                    messages[index] = ChatMessage(
                        id = pendingId,
                        sender = MessageSender.KOFU,
                        text = "Error del motor: ${e.localizedMessage ?: "Fallo de conexión"}",
                        isPending = false
                    )
                }
            } finally {
                isSending = false
            }
        }
    }

    // Scroll al último mensaje automáticamente
    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // HEADER SUPERIOR LIMPIO Y MODERNO
            ModernTopAppBar(
                repository = repository,
                serverReady = serverReady,
                activeBackend = activeBackend,
                currentModelName = currentModelName,
                isDebugActive = repository.isDebugMode,
                onToggleDebug = {
                    repository.isDebugMode = !repository.isDebugMode
                    repository.savePreferences(context)
                },
                onOpenModelSelector = { showModelDialog = true },
                onOpenSettings = { showSettingsDialog = true }
            )

            // BARRA DE PROGRESO DE DESCARGA / CARGA SI APLICA
            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress!! },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp),
                    color = colors.accent,
                    trackColor = colors.surfaceVariant
                )
            }

            // HUD DE DEPURACIÓN EN VIVO (CUANDO ESTÁ ACTIVADO)
            DebugHud(
                metrics = repository.debugMetrics,
                isVisible = repository.isDebugMode,
                onClose = {
                    repository.isDebugMode = false
                    repository.savePreferences(context)
                }
            )

            // ÁREA PRINCIPAL DE CHAT (OCUPA TODO EL ALTO NATURAL)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                    contentPadding = PaddingValues(top = 10.dp, bottom = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // HERO CARD CUANDO LA CONVERSACIÓN ESTÁ INICIANDO
                    if (messages.size <= 1) {
                        item {
                            HeroWelcomeCard(
                                repository = repository,
                                onPromptSelected = { prompt ->
                                    if (serverReady) {
                                        sendMessage(prompt)
                                    } else {
                                        inputText = prompt
                                    }
                                },
                                onOpenTerms = { showTermsDialog = true }
                            )
                        }
                    } else {
                        items(messages, key = { it.id }) { msg ->
                            ModernChatBubble(
                                message = msg,
                                userBubbleColor = userBubbleColor,
                                userBubbleText = userBubbleText,
                                kofuBubbleColor = colors.kofuBubble,
                                kofuBubbleBorder = colors.kofuBubbleBorder,
                                kofuTextColor = kofuTextColor,
                                accentColor = colors.accent,
                                fontScale = currentFontScale
                            )
                        }
                    }
                }
            }

            // BARRA DE ENTRADA MODERNA CON BORDES DEFINIDOS (10.dp)
            Surface(
                color = surfaceColor,
                tonalElevation = 6.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            ) {
                Column {
                    HorizontalDivider(color = colors.divider, thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            enabled = serverReady,
                            placeholder = {
                                Text(
                                    text = if (serverReady) "Escribe tu consulta a Kofu..." else "Conectando al servidor o motor...",
                                    color = onSurfaceSecondary,
                                    fontSize = currentFontScale.bodySp.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = false,
                            maxLines = 4,
                            shape = KofuDesignSystem.InputShape,
                            textStyle = TextStyle(
                                color = onSurface,
                                fontSize = currentFontScale.bodySp.sp,
                                lineHeight = (currentFontScale.bodySp + 4).sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = inputBg,
                                unfocusedContainerColor = inputBg,
                                disabledContainerColor = inputBg.copy(alpha = 0.5f),
                                focusedTextColor = onSurface,
                                unfocusedTextColor = onSurface,
                                disabledTextColor = onSurfaceSecondary,
                                focusedBorderColor = inputBorderFocused,
                                unfocusedBorderColor = inputBorder,
                                disabledBorderColor = inputBorder.copy(alpha = 0.4f),
                                cursorColor = colors.accent
                            )
                        )

                        val canSend = inputText.isNotBlank() && !isSending && serverReady
                        val currentBtnColor by animateColorAsState(
                            targetValue = if (isSending) colors.errorAccent else if (canSend) sendBtnColor else sendBtnDisabled,
                            animationSpec = tween(200),
                            label = "sendBtnAnim"
                        )

                        // Botón de Enviar con forma moderna menos redondeada (8dp - 10dp)
                        Surface(
                            shape = KofuDesignSystem.ButtonShape,
                            color = currentBtnColor,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(KofuDesignSystem.ButtonShape)
                                .clickable(enabled = isSending || canSend) {
                                    if (isSending) {
                                        repository.cancelGeneration()
                                        isSending = false
                                    } else if (canSend) {
                                        sendMessage(inputText)
                                    }
                                }
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Icon(
                                    imageVector = if (isSending) Icons.Default.Close else Icons.AutoMirrored.Filled.Send,
                                    contentDescription = if (isSending) "Detener" else "Enviar",
                                    tint = if (isSending || canSend) colors.sendButtonText else onSurfaceSecondary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // MODAL DE AJUSTES EXTENSO
    SettingsDialog(
        isOpen = showSettingsDialog,
        repository = repository,
        themeMode = themeMode,
        onChangeThemeMode = onChangeThemeMode,
        fontScale = fontScale,
        onChangeFontScale = onChangeFontScale,
        onOpenTerms = { showTermsDialog = true },
        onClearChat = {
            messages.clear()
            messages.add(
                ChatMessage(
                    sender = MessageSender.KOFU,
                    text = WELCOME_MESSAGE
                )
            )
        },
        onDismiss = { showSettingsDialog = false }
    )

    // MODAL DE TÉRMINOS Y PRIVACIDAD
    TermsDialog(
        isOpen = showTermsDialog,
        onDismiss = { showTermsDialog = false }
    )

    // MODAL DE SELECCIÓN DE MODELO
    if (showModelDialog) {
        ModelSelectorDialog(
            repository = repository,
            onDismiss = { showModelDialog = false },
            onSelectCustomFile = {
                showModelDialog = false
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        )
    }
}

/**
 * Top App Bar moderno y legible con bordes precisos, badges de estado y botones accesibles.
 */
@Composable
private fun ModernTopAppBar(
    repository: LlamaRepository,
    serverReady: Boolean,
    activeBackend: InferenceBackend,
    currentModelName: String,
    isDebugActive: Boolean,
    onToggleDebug: () -> Unit,
    onOpenModelSelector: () -> Unit,
    onOpenSettings: () -> Unit
) {
    val colors = LocalKofuColors.current

    Surface(
        color = colors.surface,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // LADO IZQUIERDO: Nombre y Estado de Inferencia
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Kofu",
                        fontWeight = FontWeight.ExtraBold,
                        color = colors.onSurface,
                        fontSize = 20.sp,
                        letterSpacing = (-0.5).sp
                    )

                    // Indicador de Estado y Backend
                    Surface(
                        color = if (serverReady) colors.accentSoft else colors.surfaceVariant,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .clip(KofuDesignSystem.ButtonShape)
                            .border(
                                1.dp,
                                if (serverReady) colors.accent.copy(alpha = 0.4f) else colors.divider,
                                KofuDesignSystem.ButtonShape
                            )
                            .clickable { onOpenModelSelector() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(if (serverReady) colors.successAccent else colors.warningAccent)
                            )
                            Text(
                                text = if (serverReady) {
                                    if (activeBackend == InferenceBackend.OLLAMA_SERVER) "Ollama Laptop" else activeBackend.badgeLabel
                                } else "Conectando...",
                                color = if (serverReady) colors.accent else colors.onSurfaceSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                // LADO DERECHO: Botón Debug, Selector de Modelo y Ajustes
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Botón de Alternancia de Debug
                    Surface(
                        color = if (isDebugActive) colors.accent else colors.surfaceVariant,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .clip(KofuDesignSystem.ButtonShape)
                            .clickable { onToggleDebug() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = if (isDebugActive) "🐞 HUD ON" else "🐞 HUD",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isDebugActive) colors.sendButtonText else colors.onSurfaceSecondary
                            )
                        }
                    }

                    // Botón de Ajustes (Engranaje)
                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Ajustes",
                            tint = colors.onSurface,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.divider, thickness = 1.dp)
        }
    }
}

/**
 * Hero Card elegante que aparece al inicio con información clave, garantías de privacidad y sugerencias rápidas.
 */
@Composable
private fun HeroWelcomeCard(
    repository: LlamaRepository,
    onPromptSelected: (String) -> Unit,
    onOpenTerms: () -> Unit
) {
    val colors = LocalKofuColors.current

    Surface(
        color = colors.surfaceVariant,
        shape = KofuDesignSystem.CardShape,
        modifier = Modifier
            .fillMaxWidth()
            .clip(KofuDesignSystem.CardShape)
            .border(1.dp, colors.divider, KofuDesignSystem.CardShape)
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        color = colors.accentSoft,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Text(text = "🧠", fontSize = 16.sp)
                        }
                    }
                    Column {
                        Text(
                            text = "Kofu Assistant v2.0",
                            fontWeight = FontWeight.ExtraBold,
                            color = colors.onSurface,
                            fontSize = 15.sp
                        )
                        Text(
                            text = "Inferencia Local • Google Gemma",
                            color = colors.onSurfaceSecondary,
                            fontSize = 11.sp
                        )
                    }
                }

                Surface(
                    color = colors.surface,
                    shape = KofuDesignSystem.ButtonShape,
                    modifier = Modifier
                        .clip(KofuDesignSystem.ButtonShape)
                        .border(1.dp, colors.divider, KofuDesignSystem.ButtonShape)
                        .clickable { onOpenTerms() }
                ) {
                    Text(
                        text = "📜 Términos",
                        fontSize = 10.sp,
                        color = colors.accent,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }

            Text(
                text = "Tu asistente privado conectado en tiempo real a tu servidor Ollama o motor local ARM64. Sin recopilación de datos.",
                color = colors.onSurfaceSecondary,
                fontSize = 12.sp,
                lineHeight = 16.sp
            )

            HorizontalDivider(color = colors.divider.copy(alpha = 0.5f), thickness = 1.dp)

            Text(
                text = "Consultas de inicio rápido:",
                color = colors.onSurface,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold
            )

            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                QUICK_PROMPTS.forEach { prompt ->
                    Surface(
                        color = colors.surface,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.ButtonShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.ButtonShape)
                            .clickable {
                                val clean = prompt.substringAfter(" ").trim()
                                onPromptSelected(clean)
                            }
                    ) {
                        Text(
                            text = prompt,
                            color = colors.onSurface,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Burbuja de chat moderna con esquinas asimétricas definidas (12dp), bloques de código y botón de copiado.
 */
@Composable
fun ModernChatBubble(
    message: ChatMessage,
    userBubbleColor: Color,
    userBubbleText: Color,
    kofuBubbleColor: Color,
    kofuBubbleBorder: Color,
    kofuTextColor: Color,
    accentColor: Color,
    fontScale: KofuFontScale
) {
    val isUser = message.sender == MessageSender.USER
    val colors = LocalKofuColors.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            Surface(
                color = userBubbleColor,
                shape = KofuDesignSystem.UserBubbleShape,
                modifier = Modifier
                    .widthIn(max = 300.dp)
                    .clip(KofuDesignSystem.UserBubbleShape)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Text(
                        text = "Tú",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = userBubbleText.copy(alpha = 0.8f)
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.text,
                        color = userBubbleText,
                        fontSize = fontScale.bodySp.sp,
                        lineHeight = (fontScale.bodySp + 5).sp
                    )
                }
            }
        } else {
            val infiniteTransition = rememberInfiniteTransition(label = "kofuPulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            Surface(
                color = kofuBubbleColor,
                shape = KofuDesignSystem.KofuBubbleShape,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(KofuDesignSystem.KofuBubbleShape)
                    .border(1.dp, kofuBubbleBorder, KofuDesignSystem.KofuBubbleShape)
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(7.dp)
                                    .clip(CircleShape)
                                    .background(accentColor)
                            )
                            Text(
                                text = "Kofu Assistant",
                                fontWeight = FontWeight.Bold,
                                fontSize = 11.sp,
                                color = accentColor
                            )
                        }

                        // Botón de Copiar Mensaje Completo
                        Text(
                            text = "Copiar",
                            color = colors.onSurfaceSecondary,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(KofuDesignSystem.ButtonShape)
                                .clickable {
                                    clipboard.setText(AnnotatedString(message.text))
                                    Toast.makeText(context, "Respuesta copiada", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    FormattedMessageContent(
                        text = message.text,
                        textColor = kofuTextColor,
                        isPending = message.isPending,
                        pulseAlpha = pulseAlpha,
                        fontScale = fontScale
                    )
                }
            }
        }
    }
}

/**
 * Renderizador de contenido de mensaje con soporte para bloques de código Markdown con botón de copiado.
 */
@Composable
fun FormattedMessageContent(
    text: String,
    textColor: Color,
    isPending: Boolean,
    pulseAlpha: Float,
    fontScale: KofuFontScale
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val codeBlockRegex = remember { Regex("```([a-zA-Z0-9_-]*)\\n?([\\s\\S]*?)```") }

    if (!text.contains("```")) {
        Text(
            text = text,
            fontSize = fontScale.bodySp.sp,
            color = textColor,
            lineHeight = (fontScale.bodySp + 6).sp,
            fontWeight = if (isPending) FontWeight.Light else FontWeight.Normal,
            modifier = if (isPending) Modifier.alpha(pulseAlpha) else Modifier
        )
        return
    }

    val matches = codeBlockRegex.findAll(text).toList()
    var lastIndex = 0

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (match in matches) {
            val preText = text.substring(lastIndex, match.range.first).trim()
            if (preText.isNotEmpty()) {
                Text(
                    text = preText,
                    fontSize = fontScale.bodySp.sp,
                    color = textColor,
                    lineHeight = (fontScale.bodySp + 6).sp
                )
            }
            val lang = match.groupValues[1].ifBlank { "código" }
            val code = match.groupValues[2].trimEnd()

            Surface(
                shape = KofuDesignSystem.ButtonShape,
                color = Color(0xFF0F111A),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF262C3D), KofuDesignSystem.ButtonShape)
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.uppercase(),
                            color = Color(0xFF89B4FA),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Copiar Código",
                            color = Color(0xFFA6ADC8),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(KofuDesignSystem.ButtonShape)
                                .clickable {
                                    clipboard.setText(AnnotatedString(code))
                                    Toast.makeText(context, "Código copiado", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = code,
                        color = Color(0xFFCDD6F4),
                        fontSize = fontScale.codeSp.sp,
                        lineHeight = (fontScale.codeSp + 5).sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
            lastIndex = match.range.last + 1
        }
        if (lastIndex < text.length) {
            val postText = text.substring(lastIndex).trim()
            if (postText.isNotEmpty()) {
                Text(
                    text = postText,
                    fontSize = fontScale.bodySp.sp,
                    color = textColor,
                    lineHeight = (fontScale.bodySp + 6).sp
                )
            }
        }
    }
}

/**
 * Diálogo moderno para selección de modelos (Ollama o GGUF local ARM64).
 */
@Composable
private fun ModelSelectorDialog(
    repository: LlamaRepository,
    onDismiss: () -> Unit,
    onSelectCustomFile: () -> Unit
) {
    val colors = LocalKofuColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(text = "🧠", fontSize = 20.sp)
                Text(
                    text = "Seleccionar Modelo",
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface,
                    fontSize = 18.sp
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "Modelos disponibles para ejecución local en CPU/GPU o servidor Ollama:",
                    color = colors.onSurfaceSecondary,
                    fontSize = 12.sp
                )

                // Si hay modelos Ollama disponibles
                if (repository.ollamaAvailableModels.isNotEmpty()) {
                    Text(
                        text = "Modelos en Servidor Ollama (Laptop):",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.accent
                    )

                    repository.ollamaAvailableModels.forEach { oModel ->
                        val isCurrent = repository.ollamaModel == oModel && repository.isModelLoaded
                        Surface(
                            color = if (isCurrent) colors.accentSoft else colors.surfaceVariant,
                            shape = KofuDesignSystem.CardShape,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(KofuDesignSystem.CardShape)
                                .border(
                                    1.dp,
                                    if (isCurrent) colors.accent else colors.divider,
                                    KofuDesignSystem.CardShape
                                )
                                .clickable {
                                    repository.ollamaModel = oModel
                                    repository.currentModelName = "$oModel (Ollama)"
                                    repository.activeBackend = InferenceBackend.OLLAMA_SERVER
                                    repository.isModelLoaded = true
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = oModel,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp,
                                        color = colors.onSurface
                                    )
                                    Text(
                                        text = "Inferencia ultrarrápida vía USB / Red",
                                        fontSize = 11.sp,
                                        color = colors.onSurfaceSecondary
                                    )
                                }
                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = colors.accent,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Modelos Nativos ARM64 (HuggingFace / Archivo):",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.accent
                )

                repository.AVAILABLE_MODELS.forEach { modelOpt ->
                    val isSelected = modelOpt.id == repository.selectedModel.id
                    Surface(
                        color = if (isSelected) colors.accentSoft else colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(
                                1.dp,
                                if (isSelected) colors.accent else colors.divider,
                                KofuDesignSystem.CardShape
                            )
                            .clickable {
                                repository.selectedModel = modelOpt
                                onDismiss()
                            }
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(
                                    text = modelOpt.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 12.sp,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = modelOpt.sizeLabel,
                                    fontSize = 11.sp,
                                    color = colors.onSurfaceSecondary
                                )
                            }
                            Text(
                                text = modelOpt.description,
                                fontSize = 11.sp,
                                color = colors.onSurfaceSecondary,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                shape = KofuDesignSystem.ButtonShape,
                colors = ButtonDefaults.buttonColors(
                    containerColor = colors.accent,
                    contentColor = colors.sendButtonText
                )
            ) {
                Text("Listo", fontSize = 13.sp)
            }
        },
        dismissButton = {
            TextButton(onClick = onSelectCustomFile) {
                Text("Cargar archivo (+)", color = colors.onSurfaceSecondary, fontSize = 13.sp)
            }
        },
        containerColor = colors.surface,
        shape = KofuDesignSystem.DialogShape
    )
}
