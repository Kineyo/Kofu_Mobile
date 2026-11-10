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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import com.kofu.assistant.data.ChatCacheManager
import com.kofu.assistant.data.ChatMessageData
import com.kofu.assistant.data.ChatSession
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
    "Inferencia local activa con llama.cpp ARM64. Modelo listo para consultas."

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

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    // Persistencia y gestión de sesiones de chat (caché local en disco)
    val sessions = remember {
        mutableStateListOf<ChatSession>().apply {
            addAll(ChatCacheManager.loadSessions(context))
        }
    }

    var activeSessionId by remember {
        mutableStateOf(sessions.firstOrNull()?.id ?: UUID.randomUUID().toString())
    }

    val currentSession = sessions.find { it.id == activeSessionId } ?: sessions.firstOrNull() ?: run {
        val newS = ChatSession(
            title = "Conversación inicial",
            messages = mutableListOf(ChatMessageData(sender = "KOFU", text = WELCOME_MESSAGE))
        )
        sessions.add(newS)
        ChatCacheManager.saveSessions(context, sessions)
        newS
    }

    val messages = remember(currentSession.id) {
        mutableStateListOf<ChatMessage>().apply {
            if (currentSession.messages.isEmpty()) {
                add(ChatMessage(sender = MessageSender.KOFU, text = WELCOME_MESSAGE))
            } else {
                addAll(currentSession.messages.map {
                    ChatMessage(
                        id = it.id,
                        sender = if (it.sender == "USER") MessageSender.USER else MessageSender.KOFU,
                        text = it.text
                    )
                })
            }
        }
    }

    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }

    var showSettingsDialog by remember { mutableStateOf(false) }
    var showTermsDialog by remember { mutableStateOf(false) }
    var showModelDialog by remember { mutableStateOf(false) }

    val serverReady = repository.isModelLoaded
    val activeBackend = repository.activeBackend

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            repository.loadModel(context, it, scope)
        }
    }

    fun persistMessages() {
        currentSession.messages.clear()
        currentSession.messages.addAll(
            messages.filter { !it.isPending && it.text.isNotBlank() }.map {
                ChatMessageData(
                    id = it.id,
                    sender = if (it.sender == MessageSender.USER) "USER" else "KOFU",
                    text = it.text
                )
            }
        )
        currentSession.updatedAt = System.currentTimeMillis()
        ChatCacheManager.saveSessions(context, sessions)
    }

    fun sendMessage(rawText: String) {
        val userMsgText = rawText.trim()
        if (userMsgText.isBlank() || isSending || !serverReady) return

        isSending = true
        inputText = ""

        val userMsg = ChatMessage(sender = MessageSender.USER, text = userMsgText)
        messages.add(userMsg)

        if (currentSession.title == "Conversación inicial" || currentSession.title == "Nueva conversación") {
            currentSession.title = if (userMsgText.length > 28) userMsgText.take(28) + "..." else userMsgText
        }
        persistMessages()

        val pendingId = UUID.randomUUID().toString()
        messages.add(
            ChatMessage(
                id = pendingId,
                sender = MessageSender.KOFU,
                text = "Procesando con llama.cpp...",
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
                persistMessages()
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
                persistMessages()
            } finally {
                isSending = false
            }
        }
    }

    LaunchedEffect(messages.size, messages.lastOrNull()?.text) {
        if (messages.isNotEmpty()) {
            listState.scrollToItem(messages.size - 1)
        }
    }

    fun createNewSession() {
        val newS = ChatSession(
            title = "Nueva conversación",
            messages = mutableListOf(ChatMessageData(sender = "KOFU", text = WELCOME_MESSAGE))
        )
        sessions.add(0, newS)
        ChatCacheManager.saveSessions(context, sessions)
        activeSessionId = newS.id
    }

    fun togglePin(session: ChatSession) {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx != -1) {
            val updated = sessions[idx].copy(isPinned = !sessions[idx].isPinned, updatedAt = System.currentTimeMillis())
            sessions[idx] = updated
            ChatCacheManager.saveSessions(context, sessions)
        }
    }

    fun toggleArchive(session: ChatSession) {
        val idx = sessions.indexOfFirst { it.id == session.id }
        if (idx != -1) {
            val updated = sessions[idx].copy(isArchived = !sessions[idx].isArchived, updatedAt = System.currentTimeMillis())
            sessions[idx] = updated
            ChatCacheManager.saveSessions(context, sessions)
        }
    }

    fun deleteSession(session: ChatSession) {
        sessions.removeAll { it.id == session.id }
        if (sessions.isEmpty()) {
            val fresh = ChatSession(
                title = "Nueva conversación",
                messages = mutableListOf(ChatMessageData(sender = "KOFU", text = WELCOME_MESSAGE))
            )
            sessions.add(fresh)
        }
        ChatCacheManager.saveSessions(context, sessions)
        if (activeSessionId == session.id) {
            activeSessionId = sessions.first().id
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = colors.surface,
                modifier = Modifier.width(310.dp)
            ) {
                ChatsDrawerContent(
                    sessions = sessions,
                    activeSessionId = activeSessionId,
                    onSelectSession = { s -> activeSessionId = s.id },
                    onNewChat = { createNewSession() },
                    onTogglePin = { s -> togglePin(s) },
                    onToggleArchive = { s -> toggleArchive(s) },
                    onDeleteSession = { s -> deleteSession(s) },
                    onCloseDrawer = { scope.launch { drawerState.close() } }
                )
            }
        }
    ) {
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
                // TOP BAR COMPACTA CON BOTÓN DE MENÚ LATERAL DE CHATS
                CompactTopAppBar(
                    serverReady = serverReady,
                    activeBackend = activeBackend,
                    onOpenDrawer = { scope.launch { drawerState.open() } },
                    onOpenModelSelector = { showModelDialog = true },
                    onOpenSettings = { showSettingsDialog = true }
                )

                // INDICADOR DE DESCARGA EN SEGUNDO PLANO O PAUSADA/INTERRUMPIDA
                val isDownloading = repository.isDownloadingModel
                val isPaused = repository.isDownloadPaused || (repository.getPartialModelFile(context) != null && !repository.isModelLoaded)
                if (isDownloading || isPaused) {
                    Surface(
                        color = colors.surfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        shape = KofuDesignSystem.CardShape,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isPaused && !isDownloading) colors.accent.copy(alpha = 0.5f) else colors.divider
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = repository.downloadStatusText.ifBlank {
                                        if (isDownloading) "Descargando modelo en segundo plano..." else "Descarga en pausa. Toca Reanudar."
                                    },
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isPaused && !isDownloading) colors.accent else colors.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "${(repository.downloadProgress * 100).toInt()}%",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = colors.accent
                                    )
                                    if (isDownloading) {
                                        Text(
                                            text = "Pausar",
                                            fontSize = 10.sp,
                                            color = colors.onSurfaceSecondary,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                repository.pauseDownload(context)
                                            }
                                        )
                                    } else {
                                        Text(
                                            text = "Reanudar",
                                            fontSize = 10.sp,
                                            color = colors.accent,
                                            fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable {
                                                repository.resumeDownload(context)
                                            }
                                        )
                                    }
                                    Text(
                                        text = "Cancelar",
                                        fontSize = 10.sp,
                                        color = colors.errorAccent,
                                        fontWeight = FontWeight.Bold,
                                        modifier = Modifier.clickable {
                                            repository.cancelDownload(context)
                                        }
                                    )
                                }
                            }
                            LinearProgressIndicator(
                                progress = { repository.downloadProgress },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(3.dp),
                                color = if (isPaused && !isDownloading) colors.onSurfaceSecondary else colors.accent,
                                trackColor = colors.inputBackground
                            )
                        }
                    }
                }

                // HUD DE TELEMETRÍA (Si está activo)
                if (repository.isDebugMode) {
                    DebugHud(
                        metrics = repository.debugMetrics,
                        isVisible = true,
                        onClose = {
                            repository.isDebugMode = false
                            repository.savePreferences(context)
                        }
                    )
                }

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

                // BARRA DE ENTRADA
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
            messages.add(ChatMessage(sender = MessageSender.KOFU, text = WELCOME_MESSAGE))
            currentSession.messages.clear()
            currentSession.messages.add(ChatMessageData(sender = "KOFU", text = WELCOME_MESSAGE))
            ChatCacheManager.saveSessions(context, sessions)
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
 * Barra lateral (Drawer) con persistencia de conversaciones y menú de 3 puntos (Fijar, Archivar, Eliminar).
 */
@Composable
private fun ChatsDrawerContent(
    sessions: List<ChatSession>,
    activeSessionId: String,
    onSelectSession: (ChatSession) -> Unit,
    onNewChat: () -> Unit,
    onTogglePin: (ChatSession) -> Unit,
    onToggleArchive: (ChatSession) -> Unit,
    onDeleteSession: (ChatSession) -> Unit,
    onCloseDrawer: () -> Unit
) {
    val colors = LocalKofuColors.current
    var selectedTab by remember { mutableStateOf(0) } // 0: Activos, 1: Archivados

    val filteredSessions = remember(sessions, selectedTab) {
        sessions
            .filter { if (selectedTab == 0) !it.isArchived else it.isArchived }
            .sortedWith(compareByDescending<ChatSession> { it.isPinned }.thenByDescending { it.updatedAt })
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Encabezado
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "HISTORIAL",
                    fontWeight = FontWeight.Black,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 14.sp,
                    letterSpacing = 1.sp,
                    color = colors.onSurface
                )
                Text(
                    text = "Persistencia local en caché",
                    fontSize = 11.sp,
                    color = colors.onSurfaceSecondary
                )
            }

            IconButton(onClick = onCloseDrawer, modifier = Modifier.size(28.dp)) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Cerrar",
                    tint = colors.onSurfaceSecondary,
                    modifier = Modifier.size(16.dp)
                )
            }
        }

        HorizontalDivider(color = colors.divider, thickness = 1.dp)

        // Botón Nueva Conversación
        Button(
            onClick = {
                onNewChat()
                onCloseDrawer()
            },
            shape = KofuDesignSystem.ButtonShape,
            colors = ButtonDefaults.buttonColors(
                containerColor = colors.accent,
                contentColor = colors.sendButtonText
            ),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "Nueva Conversación",
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Pestañas Activos / Archivados
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            listOf("Activos", "Archivados").forEachIndexed { index, tabTitle ->
                val isSelected = selectedTab == index
                Surface(
                    color = if (isSelected) colors.accentSoft else colors.surfaceVariant,
                    shape = KofuDesignSystem.ButtonShape,
                    modifier = Modifier
                        .weight(1f)
                        .clip(KofuDesignSystem.ButtonShape)
                        .border(
                            1.dp,
                            if (isSelected) colors.onSurfaceSecondary else colors.divider,
                            KofuDesignSystem.ButtonShape
                        )
                        .clickable { selectedTab = index }
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(vertical = 6.dp)
                    ) {
                        Text(
                            text = tabTitle,
                            fontSize = 11.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                            fontFamily = FontFamily.Monospace,
                            color = colors.onSurface
                        )
                    }
                }
            }
        }

        // Lista de chats
        if (filteredSessions.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (selectedTab == 0) "Sin conversaciones activas" else "Sin conversaciones archivadas",
                    fontSize = 11.sp,
                    color = colors.onSurfaceMuted,
                    fontFamily = FontFamily.Monospace
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(filteredSessions, key = { it.id }) { session ->
                    val isActive = session.id == activeSessionId
                    var menuExpanded by remember { mutableStateOf(false) }

                    Surface(
                        color = if (isActive) colors.accentSoft else colors.surfaceVariant,
                        shape = KofuDesignSystem.CardShape,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(KofuDesignSystem.CardShape)
                            .border(
                                1.dp,
                                if (isActive) colors.onSurfaceSecondary else colors.divider,
                                KofuDesignSystem.CardShape
                            )
                            .clickable {
                                onSelectSession(session)
                                onCloseDrawer()
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    if (session.isPinned) {
                                        Icon(
                                            imageVector = Icons.Default.PushPin,
                                            contentDescription = "Fijado",
                                            tint = colors.warningAccent,
                                            modifier = Modifier.size(12.dp)
                                        )
                                    }
                                    Text(
                                        text = session.title,
                                        fontSize = 12.sp,
                                        fontWeight = if (isActive || session.isPinned) FontWeight.Bold else FontWeight.Normal,
                                        color = colors.onSurface,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                val lastMsg = session.messages.lastOrNull()?.text ?: "Sin mensajes"
                                Text(
                                    text = lastMsg,
                                    fontSize = 10.5.sp,
                                    color = colors.onSurfaceSecondary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }

                            Box {
                                IconButton(
                                    onClick = { menuExpanded = true },
                                    modifier = Modifier.size(26.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "Opciones",
                                        tint = colors.onSurfaceSecondary,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }

                                DropdownMenu(
                                    expanded = menuExpanded,
                                    onDismissRequest = { menuExpanded = false }
                                ) {
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (session.isPinned) "Desfijar" else "Fijar al inicio")
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.PushPin,
                                                contentDescription = null,
                                                tint = if (session.isPinned) colors.warningAccent else colors.onSurface
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onTogglePin(session)
                                        }
                                    )
                                    DropdownMenuItem(
                                        text = {
                                            Text(if (session.isArchived) "Desarchivar" else "Archivar")
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = if (session.isArchived) Icons.Default.Unarchive else Icons.Default.Archive,
                                                contentDescription = null
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onToggleArchive(session)
                                        }
                                    )
                                    HorizontalDivider()
                                    DropdownMenuItem(
                                        text = {
                                            Text("Eliminar", color = colors.errorAccent)
                                        },
                                        leadingIcon = {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = null,
                                                tint = colors.errorAccent
                                            )
                                        },
                                        onClick = {
                                            menuExpanded = false
                                            onDeleteSession(session)
                                        }
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

/**
 * Top App Bar formal con botón para desplegar la barra de chats.
 */
@Composable
private fun CompactTopAppBar(
    serverReady: Boolean,
    activeBackend: InferenceBackend,
    onOpenDrawer: () -> Unit,
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
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    IconButton(
                        onClick = onOpenDrawer,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Menu,
                            contentDescription = "Historial de chats",
                            tint = colors.onSurface,
                            modifier = Modifier.size(18.dp)
                        )
                    }

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

            HorizontalDivider(color = colors.divider, thickness = 1.dp)
        }
    }
}

/**
 * Hero Card formal, minimalista y compacta.
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
                        text = "Kofu Assistant v2.1.5",
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface,
                        fontSize = 14.sp
                    )
                    Text(
                        text = "Motor llama.cpp nativo ARM64 • Privacidad soberana",
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

    // Si durante el streaming hay un bloque de código abierto sin cerrar, lo cerramos virtualmente para renderizarlo inmediatamente
    val isUnclosedCodeBlock = text.contains("```") && (text.split("```").size % 2 == 0)
    val renderText = if (isUnclosedCodeBlock) "$text\n```" else text

    if (!renderText.contains("```")) {
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

    val matches = codeBlockRegex.findAll(renderText).toList()
    var lastIndex = 0

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (match in matches) {
            val preText = renderText.substring(lastIndex, match.range.first).trim()
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
        if (lastIndex < renderText.length) {
            val postText = renderText.substring(lastIndex).trim()
            if (postText.isNotEmpty() && (!isUnclosedCodeBlock || postText != "```")) {
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
 * Diálogo formal para selector de modelos.
 */
@Composable
private fun FormalModelDialog(
    repository: LlamaRepository,
    onDismiss: () -> Unit,
    onSelectCustomFile: () -> Unit
) {
    val colors = LocalKofuColors.current
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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
                    text = "MODELOS NATIVOS ARM64 (llama.cpp)",
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
                                repository.startAutoDownloadIfNeeded(context, scope)
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
                            Column {
                                Text(
                                    text = modelOpt.shortName,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    color = colors.onSurface
                                )
                                Text(
                                    text = modelOpt.name,
                                    fontSize = 10.sp,
                                    color = colors.onSurfaceSecondary
                                )
                            }
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
