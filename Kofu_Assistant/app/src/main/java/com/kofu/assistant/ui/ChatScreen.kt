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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
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
    val isPending: Boolean = false
)

private val FORMAL_QUICK_PROMPTS = listOf(
    "Explica el modelo de memoria y punteros en C++ con un ejemplo",
    "Arquitectura de concurrencia y Coroutines en Kotlin",
    "Diferencias de latencia y soberanía entre inferencia local y cloud",
    "Implementa una función recursiva para la serie Fibonacci en Python"
)

private const val WELCOME_MESSAGE =
    "Inferencia local activa. Modelo compilado y listo para consultas."

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

    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }

    var downloadProgress by remember { mutableStateOf<Float?>(null) }

    val serverReady = repository.isModelLoaded
    val activeBackend = repository.activeBackend
    val currentModelName = repository.currentModelName

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
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
                text = "Procesando...",
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
                            text = "Sin respuesta del motor.",
                            isPending = false
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Inference error", e)
                val index = messages.indexOfFirst { it.id == pendingId }
                if (index != -1) {
                    messages[index] = ChatMessage(
                        id = pendingId,
                        sender = MessageSender.KOFU,
                        text = "Error de motor: ${e.localizedMessage ?: "Fallo de conexión"}",
                        isPending = false
                    )
                }
            } finally {
                isSending = false
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
        ) {
            // TOP BAR FORMAL Y COMPACTA
            CompactTopAppBar(
                serverReady = serverReady,
                activeBackend = activeBackend,
                isDebugActive = repository.isDebugMode,
                onToggleDebug = {
                    repository.isDebugMode = !repository.isDebugMode
                    repository.savePreferences(context)
                },
                onOpenModelSelector = { showModelDialog = true },
                onOpenSettings = { showSettingsDialog = true }
            )

            if (downloadProgress != null) {
                LinearProgressIndicator(
                    progress = { downloadProgress!! },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp),
                    color = colors.accent,
                    trackColor = colors.surfaceVariant
                )
            }

            // HUD DE TELEMETRÍA COMPACTO
            DebugHud(
                metrics = repository.debugMetrics,
                isVisible = repository.isDebugMode,
                onClose = {
                    repository.isDebugMode = false
                    repository.savePreferences(context)
                }
            )

            // ÁREA DE CONVERSACIÓN PRINCIPAL
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 12.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // HERO CARD INICIAL FORMAL
                    if (messages.size <= 1) {
                        item {
                            FormalHeroCard(
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
                            FormalChatBubble(
                                message = msg,
                                userBubbleColor = colors.userBubble,
                                userBubbleText = colors.userBubbleText,
                                kofuBubbleColor = colors.kofuBubble,
                                kofuBubbleBorder = colors.kofuBubbleBorder,
                                kofuTextColor = colors.kofuText,
                                fontScale = currentFontScale
                            )
                        }
                    }
                }
            }

            // BARRA DE ENTRADA COMPACTA
            // En modo edge-to-edge con adjustResize, safeDrawing.only(Bottom) calcula
            // la unión exacta entre la barra de navegación (teclado cerrado) y el IME (teclado abierto).
            // Esto elimina completamente el bug de doble padding que enviaba el cuadro de texto muy arriba.
            Surface(
                color = colors.surface,
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsPadding(
                        WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom)
                    )
            ) {
                Column {
                    HorizontalDivider(color = colors.divider, thickness = 1.dp)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            enabled = serverReady,
                            placeholder = {
                                Text(
                                    text = if (serverReady) "Enviar mensaje a Kofu..." else "Esperando conexión con el motor...",
                                    color = colors.onSurfaceSecondary,
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
                                color = colors.onSurface,
                                fontSize = currentFontScale.bodySp.sp,
                                lineHeight = (currentFontScale.bodySp + 4).sp
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = colors.inputBackground,
                                unfocusedContainerColor = colors.inputBackground,
                                disabledContainerColor = colors.inputBackground.copy(alpha = 0.5f),
                                focusedTextColor = colors.onSurface,
                                unfocusedTextColor = colors.onSurface,
                                disabledTextColor = colors.onSurfaceSecondary,
                                focusedBorderColor = colors.inputBorderFocused,
                                unfocusedBorderColor = colors.inputBorder,
                                disabledBorderColor = colors.inputBorder.copy(alpha = 0.4f),
                                cursorColor = colors.accent
                            )
                        )

                        val canSend = inputText.isNotBlank() && !isSending && serverReady
                        val currentBtnColor by animateColorAsState(
                            targetValue = if (isSending) colors.errorAccent else if (canSend) colors.sendButton else colors.sendButtonDisabled,
                            animationSpec = tween(150),
                            label = "btnColor"
                        )

                        Surface(
                            shape = KofuDesignSystem.ButtonShape,
                            color = currentBtnColor,
                            modifier = Modifier
                                .size(42.dp)
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
                                    tint = if (isSending) Color.White else if (canSend) colors.sendButtonText else colors.onSurfaceMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // DIÁLOGOS
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

    TermsDialog(
        isOpen = showTermsDialog,
        onDismiss = { showTermsDialog = false }
    )

    if (showModelDialog) {
        FormalModelDialog(
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
 * Top App Bar sobrio, formal y compacto sin emojis.
 */
@Composable
private fun CompactTopAppBar(
    serverReady: Boolean,
    activeBackend: InferenceBackend,
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
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Título e indicador de estado sobrio
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "KOFU",
                        fontWeight = FontWeight.Black,
                        color = colors.onSurface,
                        fontSize = 16.sp,
                        letterSpacing = 1.2.sp,
                        fontFamily = FontFamily.Monospace
                    )

                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .clip(KofuDesignSystem.ButtonShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.ButtonShape)
                            .clickable { onOpenModelSelector() }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(5.dp),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(KofuDesignSystem.ButtonShape)
                                    .background(if (serverReady) colors.successAccent else colors.warningAccent)
                            )
                            Text(
                                text = if (serverReady) {
                                    if (activeBackend == InferenceBackend.OLLAMA_SERVER) "OLLAMA" else activeBackend.badgeLabel.uppercase()
                                } else "OFFLINE",
                                color = colors.onSurface,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }

                // Acciones: Toggle DEBUG y Ajustes
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Surface(
                        color = if (isDebugActive) colors.accentSoft else colors.surfaceVariant,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .clip(KofuDesignSystem.ButtonShape)
                            .border(
                                1.dp,
                                if (isDebugActive) colors.onSurfaceSecondary else colors.divider,
                                KofuDesignSystem.ButtonShape
                            )
                            .clickable { onToggleDebug() }
                    ) {
                        Text(
                            text = "DEBUG",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            color = if (isDebugActive) colors.onSurface else colors.onSurfaceMuted,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 5.dp)
                        )
                    }

                    IconButton(
                        onClick = onOpenSettings,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Ajustes",
                            tint = colors.onSurfaceSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            HorizontalDivider(color = colors.divider, thickness = 1.dp)
        }
    }
}

/**
 * Hero Card formal, minimalista y compacta. Sin emojis.
 */
@Composable
private fun FormalHeroCard(
    onPromptSelected: (String) -> Unit,
    onOpenTerms: () -> Unit
) {
    val colors = LocalKofuColors.current

    Surface(
        color = colors.surface,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Kofu Assistant v2.0",
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Motor de inferencia local • Privacidad soberana",
                        color = colors.onSurfaceSecondary,
                        fontSize = 11.sp
                    )
                }

                Surface(
                    color = colors.surfaceVariant,
                    shape = KofuDesignSystem.ButtonShape,
                    modifier = Modifier
                        .clip(KofuDesignSystem.ButtonShape)
                        .border(1.dp, colors.divider, KofuDesignSystem.ButtonShape)
                        .clickable { onOpenTerms() }
                ) {
                    Text(
                        text = "Términos",
                        fontSize = 10.sp,
                        color = colors.onSurfaceSecondary,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                    )
                }
            }

            HorizontalDivider(color = colors.divider, thickness = 1.dp)

            Text(
                text = "CONSULTAS RECOMENDADAS",
                color = colors.onSurfaceMuted,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                letterSpacing = 0.5.sp
            )

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FORMAL_QUICK_PROMPTS.forEach { prompt ->
                    Surface(
                        color = colors.surfaceVariant,
                        shape = KofuDesignSystem.ButtonShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.ButtonShape)
                            .border(1.dp, colors.divider, KofuDesignSystem.ButtonShape)
                            .clickable { onPromptSelected(prompt) }
                    ) {
                        Text(
                            text = prompt,
                            color = colors.onSurface,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Normal,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Burbuja de chat formal con bordes arquitectónicos (8dp) y código mono oscuro.
 */
@Composable
fun FormalChatBubble(
    message: ChatMessage,
    userBubbleColor: Color,
    userBubbleText: Color,
    kofuBubbleColor: Color,
    kofuBubbleBorder: Color,
    kofuTextColor: Color,
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
                    .border(1.dp, colors.divider, KofuDesignSystem.UserBubbleShape)
            ) {
                Column(modifier = Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                    Text(
                        text = "USUARIO",
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 9.sp,
                        color = colors.onSurfaceSecondary
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = message.text,
                        color = userBubbleText,
                        fontSize = fontScale.bodySp.sp,
                        lineHeight = (fontScale.bodySp + 4).sp
                    )
                }
            }
        } else {
            val infiniteTransition = rememberInfiniteTransition(label = "pulse")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.5f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(600),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "alpha"
            )

            Surface(
                color = kofuBubbleColor,
                shape = KofuDesignSystem.KofuBubbleShape,
                modifier = Modifier
                    .widthIn(max = 340.dp)
                    .clip(KofuDesignSystem.KofuBubbleShape)
                    .border(1.dp, kofuBubbleBorder, KofuDesignSystem.KofuBubbleShape)
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "KOFU",
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 9.sp,
                            color = colors.onSurfaceSecondary
                        )

                        Text(
                            text = "Copiar",
                            color = colors.onSurfaceMuted,
                            fontSize = 9.sp,
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier
                                .clip(KofuDesignSystem.ButtonShape)
                                .clickable {
                                    clipboard.setText(AnnotatedString(message.text))
                                    Toast.makeText(context, "Copiado", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 3.dp, vertical = 1.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    FormalFormattedContent(
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

@Composable
fun FormalFormattedContent(
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
            lineHeight = (fontScale.bodySp + 5).sp,
            fontWeight = if (isPending) FontWeight.Light else FontWeight.Normal,
            modifier = if (isPending) Modifier.alpha(pulseAlpha) else Modifier
        )
        return
    }

    val matches = codeBlockRegex.findAll(text).toList()
    var lastIndex = 0

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (match in matches) {
            val preText = text.substring(lastIndex, match.range.first).trim()
            if (preText.isNotEmpty()) {
                Text(
                    text = preText,
                    fontSize = fontScale.bodySp.sp,
                    color = textColor,
                    lineHeight = (fontScale.bodySp + 5).sp
                )
            }
            val lang = match.groupValues[1].ifBlank { "CODE" }
            val code = match.groupValues[2].trimEnd()

            Surface(
                shape = KofuDesignSystem.ButtonShape,
                color = Color(0xFF030305),
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color(0xFF1E1E26), KofuDesignSystem.ButtonShape)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = lang.uppercase(),
                            color = Color(0xFF9CA3AF),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "COPIAR",
                            color = Color(0xFFD1D5DB),
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier
                                .clip(KofuDesignSystem.ButtonShape)
                                .clickable {
                                    clipboard.setText(AnnotatedString(code))
                                    Toast.makeText(context, "Código copiado", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 4.dp, vertical = 2.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = code,
                        color = Color(0xFFE5E7EB),
                        fontSize = fontScale.codeSp.sp,
                        lineHeight = (fontScale.codeSp + 4).sp,
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
                    lineHeight = (fontScale.bodySp + 5).sp
                )
            }
        }
    }
}

/**
 * Diálogo formal para selector de modelos sin emojis.
 */
@Composable
private fun FormalModelDialog(
    repository: LlamaRepository,
    onDismiss: () -> Unit,
    onSelectCustomFile: () -> Unit
) {
    val colors = LocalKofuColors.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "Modelos Disponibles",
                fontWeight = FontWeight.Bold,
                color = colors.onSurface,
                fontSize = 16.sp,
                fontFamily = FontFamily.Monospace
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (repository.ollamaAvailableModels.isNotEmpty()) {
                    Text(
                        text = "SERVIDOR OLLAMA",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace,
                        color = colors.onSurfaceSecondary
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
                                    if (isCurrent) colors.onSurfaceSecondary else colors.divider,
                                    KofuDesignSystem.CardShape
                                )
                                .clickable {
                                    repository.ollamaModel = oModel
                                    repository.currentModelName = oModel
                                    repository.activeBackend = InferenceBackend.OLLAMA_SERVER
                                    repository.isModelLoaded = true
                                    onDismiss()
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = oModel,
                                    fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = colors.onSurface,
                                    fontFamily = FontFamily.Monospace
                                )
                                if (isCurrent) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = null,
                                        tint = colors.onSurface,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = "MODELOS NATIVOS ARM64",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    color = colors.onSurfaceSecondary
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
                                if (isSelected) colors.onSurfaceSecondary else colors.divider,
                                KofuDesignSystem.CardShape
                            )
                            .clickable {
                                repository.selectedModel = modelOpt
                                onDismiss()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = modelOpt.shortName,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                fontSize = 12.sp,
                                color = colors.onSurface
                            )
                            Text(
                                text = modelOpt.sizeLabel,
                                fontSize = 10.sp,
                                color = colors.onSurfaceSecondary,
                                fontFamily = FontFamily.Monospace
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
                Text("Cerrar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onSelectCustomFile) {
                Text("Cargar archivo", color = colors.onSurfaceSecondary, fontSize = 12.sp)
            }
        },
        containerColor = colors.surface,
        shape = KofuDesignSystem.DialogShape
    )
}
