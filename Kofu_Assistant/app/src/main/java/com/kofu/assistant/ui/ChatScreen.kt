package com.kofu.assistant.ui

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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kofu.assistant.repository.InferenceBackend
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.ui.theme.KofuThemeMode
import com.kofu.assistant.ui.theme.LocalKofuColors
import kotlinx.coroutines.launch
import java.util.UUID

enum class MessageSender { USER, KOFU }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val isPending: Boolean = false
)

private val QUICK_PROMPTS = listOf(
    "✨ ¿Cómo puedes ayudarme hoy?",
    "🔍 Explícame un concepto paso a paso",
    "📋 Ayúdame a organizar mi día",
    "💡 Dame ideas para un proyecto"
)

private const val WELCOME_MESSAGE =
    "¡Hola! Soy Kofu, tu asistente personal con IA local. ¿En qué te puedo ayudar hoy?"

@Composable
fun ChatScreen(
    repository: LlamaRepository,
    themeMode: KofuThemeMode = KofuThemeMode.SYSTEM,
    onChangeThemeMode: (KofuThemeMode) -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val colors = LocalKofuColors.current

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
    val headerOverlay = colors.headerOverlay

    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showThemeDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val serverReady = repository.isModelLoaded
    val activeBackend = repository.activeBackend
    val currentModelName = repository.currentModelName

    val serverStatus = when {
        serverReady -> "Modelo $currentModelName listo"
        downloadError != null -> downloadError!!
        downloadProgress != null -> "Descargando modelo inicial... ${(downloadProgress!! * 100).toInt()}%"
        else -> "Preparando motor local..."
    }
    val serverProgress: Float? = downloadProgress

    LaunchedEffect(Unit) {
        if (!repository.isModelLoaded) {
            repository.downloadDefaultModel(
                context = context,
                onProgress = { downloadProgress = it },
                onComplete = { uri ->
                    downloadProgress = null
                    downloadError = null
                    repository.loadModel(context, uri, scope)
                },
                onError = { error ->
                    downloadProgress = null
                    downloadError = "Sin modelo ($error). Toca '+' para cargar (.litertlm / .gguf)."
                }
            )
        }
    }

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
                text = "Kofu está pensando...",
                isPending = true
            )
        )

        scope.launch {
            try {
                var accumulatedText = ""
                var isFirstChunk = true

                repository.generateResponseStream(userMsgText).collect { chunk ->
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
                            text = "No pude generar una respuesta en este momento.",
                            isPending = false
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("ChatScreen", "Error during prompt generation", e)
                val index = messages.indexOfFirst { it.id == pendingId }
                if (index != -1) {
                    messages[index] = ChatMessage(
                        id = pendingId,
                        sender = MessageSender.KOFU,
                        text = "Error del motor local: ${e.localizedMessage}",
                        isPending = false
                    )
                }
            } finally {
                isSending = false
            }
        }
    }

    // Scroll to bottom when new messages arrive
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
        val glowColor = colors.accent.copy(alpha = if (colors.isDark) 0.14f else 0.08f)

        // CAPA 2: Character Layer — transparent container with ambient radial aura
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.46f)
                .align(Alignment.TopCenter)
                .padding(top = 76.dp),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(CircleShape)
                    .background(
                        Brush.radialGradient(
                            colors = listOf(glowColor, Color.Transparent)
                        )
                    )
            )
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/avatar_kofu.png")
                    .crossfade(true)
                    .build(),
                contentDescription = "Avatar de Kofu",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight(0.86f)
            )
        }

        // CAPA 1: Messages & Input Layer — bottom 54%
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.54f)
                .align(Alignment.BottomCenter)
                .imePadding()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp),
                contentPadding = PaddingValues(vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(350)) +
                                slideInVertically(
                                    animationSpec = tween(350),
                                    initialOffsetY = { it / 3 }
                                )
                    ) {
                        ChatBubble(
                            message = msg,
                            userBubbleColor = userBubbleColor,
                            userBubbleText = userBubbleText,
                            kofuBubbleColor = colors.kofuBubble,
                            kofuBubbleBorder = colors.kofuBubbleBorder,
                            kofuTextColor = kofuTextColor,
                            accentColor = colors.accent
                        )
                    }
                }
            }

            // Quick Prompts when only welcome message is present
            AnimatedVisibility(
                visible = messages.size <= 1,
                enter = fadeIn(tween(300)),
                exit = fadeOut(tween(200))
            ) {
                LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(QUICK_PROMPTS) { prompt ->
                        val cleanPrompt = prompt.substringAfter(" ").trim()
                        Surface(
                            shape = RoundedCornerShape(18.dp),
                            color = colors.surfaceVariant.copy(alpha = 0.9f),
                            modifier = Modifier
                                .clip(RoundedCornerShape(18.dp))
                                .border(
                                    width = 1.dp,
                                    color = colors.inputBorder,
                                    shape = RoundedCornerShape(18.dp)
                                )
                                .clickable(enabled = !isSending) {
                                    if (serverReady) {
                                        sendMessage(cleanPrompt)
                                    } else {
                                        inputText = cleanPrompt
                                    }
                                }
                        ) {
                            Text(
                                text = prompt,
                                color = onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }
            }

            // Input Bar
            Surface(
                color = surfaceColor.copy(alpha = 0.96f),
                tonalElevation = 4.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Column {
                    HorizontalDivider(color = colors.divider, thickness = 1.dp)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = inputText,
                            onValueChange = { inputText = it },
                            enabled = serverReady,
                            placeholder = {
                                Text(
                                    text = if (serverReady) "Escribe un mensaje a Kofu..." else serverStatus,
                                    color = onSurfaceSecondary,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            modifier = Modifier.weight(1f),
                            singleLine = false,
                            maxLines = 4,
                            shape = RoundedCornerShape(24.dp),
                            textStyle = TextStyle(color = onSurface, fontSize = 14.sp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedContainerColor = inputBg,
                                unfocusedContainerColor = inputBg,
                                disabledContainerColor = inputBg.copy(alpha = 0.6f),
                                focusedTextColor = onSurface,
                                unfocusedTextColor = onSurface,
                                disabledTextColor = onSurfaceSecondary,
                                focusedBorderColor = inputBorderFocused,
                                unfocusedBorderColor = inputBorder,
                                disabledBorderColor = inputBorder.copy(alpha = 0.5f),
                                cursorColor = colors.accent
                            )
                        )

                        Spacer(modifier = Modifier.width(8.dp))

                        val canSend = inputText.isNotBlank() && !isSending && serverReady
                        val currentBtnColor by animateColorAsState(
                            targetValue = if (canSend) sendBtnColor else sendBtnDisabled,
                            animationSpec = tween(250),
                            label = "sendBtn"
                        )

                        IconButton(
                            onClick = { sendMessage(inputText) },
                            enabled = canSend,
                            modifier = Modifier
                                .size(46.dp)
                                .clip(CircleShape)
                                .background(currentBtnColor)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Enviar mensaje",
                                tint = if (canSend) colors.sendButtonText else onSurfaceSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                }
            }
        }

        // CAPA 3: Modern Header with Gradient Overlay & Backend Badge
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            headerOverlay.copy(alpha = 0.96f),
                            headerOverlay.copy(alpha = 0.80f),
                            headerOverlay.copy(alpha = 0.0f)
                        )
                    )
                )
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .clickable {
                            filePickerLauncher.launch(arrayOf("*/*"))
                        }
                        .padding(vertical = 4.dp, horizontal = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Kofu",
                            fontWeight = FontWeight.ExtraBold,
                            color = onSurface,
                            fontSize = 22.sp
                        )

                        // Active Backend Chip (LiteRT vs GGUF Fallback)
                        BackendBadge(
                            backend = activeBackend,
                            isModelLoaded = serverReady
                        )
                    }

                    Spacer(modifier = Modifier.height(2.dp))

                    Text(
                        text = serverStatus,
                        color = if (!serverReady && downloadError != null) colors.errorAccent else onSurfaceSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    if (!serverReady && downloadError == null) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val currentProgress = serverProgress
                        if (currentProgress != null) {
                            LinearProgressIndicator(
                                progress = { currentProgress },
                                modifier = Modifier
                                    .width(160.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = colors.accent,
                                trackColor = colors.surfaceVariant
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(160.dp)
                                    .height(3.dp)
                                    .clip(RoundedCornerShape(2.dp)),
                                color = colors.accent,
                                trackColor = colors.surfaceVariant
                            )
                        }
                    }
                }

                // Right side actions: Theme Pill + Load Model (+) + Menu
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    // Quick Theme Mode Pill button
                    Surface(
                        color = colors.surfaceVariant.copy(alpha = 0.85f),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .border(1.dp, colors.divider, RoundedCornerShape(50))
                            .clickable { showThemeDialog = true }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = when (themeMode) {
                                    KofuThemeMode.LIGHT -> "☀️"
                                    KofuThemeMode.DARK -> "🌙"
                                    KofuThemeMode.SYSTEM -> "⚙️"
                                },
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = themeMode.label,
                                color = onSurface,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }

                    // Load Model Button (.litertlm / .task / .tflite / .gguf)
                    IconButton(
                        onClick = { filePickerLauncher.launch(arrayOf("*/*")) }
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Cargar modelo (.litertlm o .gguf)",
                            tint = onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // Overflow Menu
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Menú de opciones",
                                tint = onSurface
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(0.dp, 4.dp),
                            modifier = Modifier.background(surfaceColor)
                        ) {
                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Settings,
                                        contentDescription = null,
                                        tint = colors.accent
                                    )
                                },
                                text = {
                                    Column {
                                        Text(
                                            text = "Tema de la interfaz",
                                            color = onSurface,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                        Text(
                                            text = "Actual: ${themeMode.label}",
                                            color = onSurfaceSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    showThemeDialog = true
                                }
                            )

                            HorizontalDivider(color = colors.divider)

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = null,
                                        tint = onSurface
                                    )
                                },
                                text = {
                                    Column {
                                        Text(
                                            text = "Cargar modelo local",
                                            color = onSurface,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = "LiteRT (.litertlm/.task) o GGUF (.gguf)",
                                            color = onSurfaceSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                },
                                onClick = {
                                    showMenu = false
                                    filePickerLauncher.launch(arrayOf("*/*"))
                                }
                            )

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Delete,
                                        contentDescription = null,
                                        tint = onSurface
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Limpiar conversación",
                                        color = onSurface,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    messages.clear()
                                    messages.add(
                                        ChatMessage(
                                            sender = MessageSender.KOFU,
                                            text = WELCOME_MESSAGE
                                        )
                                    )
                                    showMenu = false
                                }
                            )

                            HorizontalDivider(color = colors.divider)

                            DropdownMenuItem(
                                leadingIcon = {
                                    Icon(
                                        imageVector = Icons.Default.Info,
                                        contentDescription = null,
                                        tint = onSurface
                                    )
                                },
                                text = {
                                    Text(
                                        text = "Estado del Motor / Acerca de Kofu",
                                        color = onSurface,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    showAboutDialog = true
                                    showMenu = false
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    // Theme Selection Dialog (Claro / Oscuro / Sistema)
    if (showThemeDialog) {
        AlertDialog(
            onDismissRequest = { showThemeDialog = false },
            title = {
                Text(
                    text = "Apariencia de Kofu",
                    fontWeight = FontWeight.Bold,
                    color = onSurface
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Selecciona el modo visual. Tu preferencia se guardará automáticamente.",
                        color = onSurfaceSecondary,
                        fontSize = 13.sp
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    KofuThemeMode.entries.forEach { mode ->
                        val isSelected = mode == themeMode
                        val modeIcon = when (mode) {
                            KofuThemeMode.LIGHT -> "☀️"
                            KofuThemeMode.DARK -> "🌙"
                            KofuThemeMode.SYSTEM -> "⚙️"
                        }
                        val modeDesc = when (mode) {
                            KofuThemeMode.LIGHT -> "Fondo claro perlado de alto contraste"
                            KofuThemeMode.DARK -> "Fondo obsidiana con acentos lavanda"
                            KofuThemeMode.SYSTEM -> "Se adapta automáticamente a Android"
                        }

                        Surface(
                            color = if (isSelected) colors.accentSoft else colors.surfaceVariant,
                            shape = RoundedCornerShape(14.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(14.dp))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) colors.accent else colors.divider,
                                    shape = RoundedCornerShape(14.dp)
                                )
                                .clickable {
                                    onChangeThemeMode(mode)
                                    showThemeDialog = false
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(text = modeIcon, fontSize = 18.sp)
                                    Column {
                                        Text(
                                            text = "Modo ${mode.label}",
                                            color = onSurface,
                                            fontWeight = FontWeight.SemiBold,
                                            fontSize = 14.sp
                                        )
                                        Text(
                                            text = modeDesc,
                                            color = onSurfaceSecondary,
                                            fontSize = 11.sp
                                        )
                                    }
                                }
                                if (isSelected) {
                                    Icon(
                                        imageVector = Icons.Default.Check,
                                        contentDescription = "Seleccionado",
                                        tint = colors.accent,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showThemeDialog = false }) {
                    Text("Cerrar", color = colors.accent)
                }
            },
            containerColor = surfaceColor
        )
    }

    // Engine Status & About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Text(
                    text = "Kofu Assistant • Motor Dual",
                    fontWeight = FontWeight.Bold,
                    color = onSurface,
                    fontSize = 18.sp
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = "Asistente personal inteligente con ejecución 100% local en tu dispositivo.",
                        color = onSurfaceSecondary,
                        fontSize = 13.sp
                    )

                    Surface(
                        color = colors.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .border(1.dp, colors.divider, RoundedCornerShape(12.dp))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "Diagnóstico del Motor",
                                fontWeight = FontWeight.Bold,
                                color = onSurface,
                                fontSize = 13.sp
                            )
                            Text(
                                text = "• Backend activo: ${activeBackend.displayName}",
                                color = onSurface,
                                fontSize = 12.sp
                            )
                            Text(
                                text = "• Modelo cargado: ${if (serverReady) currentModelName else "Ninguno"}",
                                color = onSurface,
                                fontSize = 12.sp
                            )
                        }
                    }

                    Text(
                        text = "Arquitectura Híbrida:\n• Primario: Google AI Edge LiteRT (.litertlm, .task, .tflite) con aceleración GPU/NPU.\n• Fallback: Llama.cpp JNI (.gguf) en CPU.",
                        color = onSurfaceSecondary,
                        fontSize = 12.sp,
                        lineHeight = 17.sp
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Entendido", color = colors.accent)
                }
            },
            containerColor = surfaceColor
        )
    }
}

@Composable
private fun BackendBadge(
    backend: InferenceBackend,
    isModelLoaded: Boolean
) {
    val colors = LocalKofuColors.current

    val (badgeText, badgeBg, badgeFg) = when {
        !isModelLoaded || backend == InferenceBackend.NONE -> Triple(
            "Sin motor",
            colors.surfaceVariant,
            colors.onSurfaceSecondary
        )
        backend == InferenceBackend.LITERT -> Triple(
            "⚡ LiteRT",
            colors.accentSoft,
            colors.accentSecondary
        )
        backend == InferenceBackend.GGUF_FALLBACK -> Triple(
            "🛡️ GGUF Fallback",
            colors.surfaceVariant,
            colors.warningAccent
        )
        else -> Triple(
            backend.displayName,
            colors.surfaceVariant,
            colors.onSurfaceSecondary
        )
    }

    Surface(
        color = badgeBg,
        shape = RoundedCornerShape(50),
        modifier = Modifier.border(
            width = 1.dp,
            color = badgeFg.copy(alpha = 0.45f),
            shape = RoundedCornerShape(50)
        )
    ) {
        Text(
            text = badgeText,
            color = badgeFg,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    userBubbleColor: Color,
    userBubbleText: Color,
    kofuBubbleColor: Color,
    kofuBubbleBorder: Color,
    kofuTextColor: Color,
    accentColor: Color
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            Box(
                modifier = Modifier
                    .widthIn(max = 290.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 20.dp,
                            bottomEnd = 6.dp
                        )
                    )
                    .background(userBubbleColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(
                        text = "Tú",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        color = userBubbleText.copy(alpha = 0.78f)
                    )
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = message.text,
                        fontSize = 14.sp,
                        lineHeight = 20.sp,
                        color = userBubbleText
                    )
                }
            }
        } else {
            val infiniteTransition = rememberInfiniteTransition(label = "kofuPending")
            val pulseAlpha by infiniteTransition.animateFloat(
                initialValue = 0.45f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(750),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "pulseAlpha"
            )

            Box(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(
                        RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 6.dp,
                            bottomEnd = 20.dp
                        )
                    )
                    .background(kofuBubbleColor.copy(alpha = 0.92f))
                    .border(
                        width = 1.dp,
                        color = kofuBubbleBorder,
                        shape = RoundedCornerShape(
                            topStart = 20.dp,
                            topEnd = 20.dp,
                            bottomStart = 6.dp,
                            bottomEnd = 20.dp
                        )
                    )
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
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
                            text = "Kofu",
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp,
                            color = accentColor
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.text,
                        fontSize = 14.sp,
                        color = kofuTextColor,
                        lineHeight = 20.sp,
                        fontWeight = if (message.isPending) FontWeight.Light else FontWeight.Normal,
                        modifier = if (message.isPending) Modifier.alpha(pulseAlpha) else Modifier
                    )
                }
            }
        }
    }
}
