package com.kofu.assistant.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.kofu.assistant.repository.LlamaRepository
import kotlinx.coroutines.launch
import java.util.UUID

enum class MessageSender { USER, PROMEIA }

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val sender: MessageSender,
    val text: String,
    val isPending: Boolean = false
)

@Composable
fun ChatScreen(
    repository: LlamaRepository,
    isDarkTheme: Boolean,
    onToggleTheme: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Dynamic colors based on theme
    val backgroundColor = if (isDarkTheme) Color(0xFF0A0A0A) else Color(0xFFFAFAFA)
    val surfaceColor = if (isDarkTheme) Color(0xFF121212) else Color(0xFFFFFFFF)
    val onSurface = if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF1A1A1A)
    val onSurfaceSecondary = if (isDarkTheme) Color(0xFF888888) else Color(0xFF666666)
    val userBubbleColor = if (isDarkTheme) Color(0xFF2A2A2A) else Color(0xFFE0E0E0)
    val userBubbleText = if (isDarkTheme) Color(0xFFE8E8E8) else Color(0xFF1A1A1A)
    val promeiaTextColor = if (isDarkTheme) Color(0xFFCCCCCC) else Color(0xFF2A2A2A)
    val inputBg = if (isDarkTheme) Color(0xFF1E1E1E) else Color(0xFFF0F0F0)
    val inputBorder = if (isDarkTheme) Color(0xFF333333) else Color(0xFFCCCCCC)
    val inputBorderFocused = if (isDarkTheme) Color(0xFF555555) else Color(0xFF999999)
    val sendBtnColor = if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF333333)
    val sendBtnDisabled = if (isDarkTheme) Color(0xFF333333) else Color(0xFFCCCCCC)
    val headerOverlay = if (isDarkTheme) Color(0xFF0A0A0A) else Color(0xFFFAFAFA)

    var inputText by remember { mutableStateOf("") }
    var isSending by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    var downloadProgress by remember { mutableStateOf<Float?>(null) }
    var downloadError by remember { mutableStateOf<String?>(null) }

    val serverReady = com.kofu.assistant.repository.LlamaRepository.isModelLoaded
    val serverStatus = when {
        serverReady -> "Modelo ${com.kofu.assistant.repository.LlamaRepository.currentModelName} Cargado"
        downloadError != null -> downloadError!!
        downloadProgress != null -> "Descargando modelo por defecto... ${(downloadProgress!! * 100).toInt()}%"
        else -> "Preparando descarga..."
    }
    val serverProgress: Float? = downloadProgress

    LaunchedEffect(Unit) {
        if (!repository.isModelLoaded) {
            repository.downloadDefaultModel(
                context = context,
                onProgress = { downloadProgress = it },
                onComplete = { uri -> 
                    downloadProgress = null
                    repository.loadModel(context, uri, scope)
                },
                onError = { error ->
                    downloadProgress = null
                    downloadError = "Error: $error. Usa '+' para cargar manualmente."
                }
            )
        }
    }

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
                sender = MessageSender.PROMEIA,
                text = "¡Hola! Soy Kofu, tu asistente personal. ¿En qué te puedo ayudar hoy?"
            )
        )
    }

    // Scroll to bottom when new messages arrive
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        // CAPA 2: Character Layer — transparent container
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.45f)
                .align(Alignment.TopCenter)
                .padding(top = 80.dp),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data("file:///android_asset/avatar_kofu.png")
                    .crossfade(true)
                    .build(),
                contentDescription = "Avatar de Kofu",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxHeight(0.85f)
            )
        }

        // CAPA 1: Messages Layer — bottom 50%
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.50f)
                .align(Alignment.BottomCenter)
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(messages, key = { it.id }) { msg ->
                    AnimatedVisibility(
                        visible = true,
                        enter = fadeIn(animationSpec = tween(400)) +
                                slideInVertically(
                                    animationSpec = tween(400),
                                    initialOffsetY = { it / 3 }
                                )
                    ) {
                        ChatBubble(
                            message = msg,
                            userBubbleColor = userBubbleColor,
                            userBubbleText = userBubbleText,
                            promeiaTextColor = promeiaTextColor,
                            onSurfaceSecondary = onSurfaceSecondary
                        )
                    }
                }
            }

            // Input Bar
            Surface(
                color = surfaceColor.copy(alpha = 0.95f),
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = inputText,
                        onValueChange = { inputText = it },
                        enabled = serverReady,
                        placeholder = {
                            Text(
                                text = if (serverReady) "Escribe un mensaje..." else serverStatus,
                                color = onSurfaceSecondary,
                                fontSize = 14.sp
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
                            disabledContainerColor = inputBg.copy(alpha = 0.5f),
                            focusedTextColor = onSurface,
                            unfocusedTextColor = onSurface,
                            disabledTextColor = onSurfaceSecondary,
                            focusedBorderColor = inputBorderFocused,
                            unfocusedBorderColor = inputBorder,
                            disabledBorderColor = inputBorder.copy(alpha = 0.5f),
                            cursorColor = onSurface
                        )
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    val canSend = inputText.isNotBlank() && !isSending && serverReady
                    val currentBtnColor by animateColorAsState(
                        targetValue = if (canSend) sendBtnColor else sendBtnDisabled,
                        animationSpec = tween(300),
                        label = "sendBtn"
                    )

                    IconButton(
                        onClick = {
                            val userMsgText = inputText.trim()
                            if (userMsgText.isNotBlank() && !isSending && serverReady) {
                                isSending = true
                                inputText = ""

                                messages.add(ChatMessage(sender = MessageSender.USER, text = userMsgText))

                                val pendingId = UUID.randomUUID().toString()
                                messages.add(
                                    ChatMessage(
                                        id = pendingId,
                                        sender = MessageSender.PROMEIA,
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
                                                    sender = MessageSender.PROMEIA,
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
                                                    sender = MessageSender.PROMEIA,
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
                                                sender = MessageSender.PROMEIA,
                                                text = "Servidor inicializándose: ${e.localizedMessage}",
                                                isPending = false
                                            )
                                        }
                                    } finally {
                                        isSending = false
                                    }
                                }
                            }
                        },
                        enabled = canSend,
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(currentBtnColor)
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Send,
                            contentDescription = "Enviar mensaje",
                            tint = if (canSend) {
                                if (isDarkTheme) Color(0xFF0A0A0A) else Color.White
                            } else {
                                onSurfaceSecondary
                            },
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }

        // Header with gradient overlay
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            headerOverlay.copy(alpha = 0.95f),
                            headerOverlay.copy(alpha = 0.7f),
                            headerOverlay.copy(alpha = 0.0f)
                        )
                    )
                )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 40.dp, start = 20.dp, end = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(
                    modifier = Modifier.clickable {
                        filePickerLauncher.launch(arrayOf("*/*"))
                    }
                ) {
                    Text(
                        text = "Kofu",
                        fontWeight = FontWeight.Bold,
                        color = onSurface,
                        fontSize = 20.sp
                    )
                    Text(
                        text = if (serverReady) "Servidor activo" else serverStatus,
                        color = if (!serverReady && serverStatus.contains("reintentar")) Color(0xFFFF5252) else onSurfaceSecondary,
                        fontSize = 12.sp
                    )
                    if (!serverReady) {
                        Spacer(modifier = Modifier.height(6.dp))
                        val currentProgress = serverProgress
                        if (currentProgress != null) {
                            LinearProgressIndicator(
                                progress = { currentProgress },
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp)),
                                color = if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF666666),
                                trackColor = if (isDarkTheme) Color(0xFF333333) else Color(0xFFE0E0E0)
                            )
                        } else {
                            LinearProgressIndicator(
                                modifier = Modifier
                                    .width(140.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp)),
                                color = if (isDarkTheme) Color(0xFFE0E0E0) else Color(0xFF666666),
                                trackColor = if (isDarkTheme) Color(0xFF333333) else Color(0xFFE0E0E0)
                            )
                        }
                    }
                }

                // Right side icons (Folder + Menu)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { filePickerLauncher.launch(arrayOf("*/*")) }) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Cargar modelo .gguf",
                            tint = onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 2-line menu button
                    Box {
                        Column(
                            modifier = Modifier
                                .clickable { showMenu = true }
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .width(22.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(onSurface)
                            )
                            Box(
                                modifier = Modifier
                                    .width(22.dp)
                                    .height(2.dp)
                                    .clip(RoundedCornerShape(1.dp))
                                    .background(onSurface)
                            )
                        }

                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            offset = DpOffset(0.dp, 4.dp),
                            modifier = Modifier.background(surfaceColor)
                        ) {
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = if (isDarkTheme) "Modo Claro" else "Modo Oscuro",
                                        color = onSurface,
                                        fontSize = 14.sp
                                    )
                                },
                                onClick = {
                                    onToggleTheme()
                                    showMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = "Acerca de Kofu",
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

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = {
                Text(
                    text = "Kofu v1.0",
                    fontWeight = FontWeight.Bold,
                    color = onSurface
                )
            },
            text = {
                Text(
                    text = "Asistente personal con IA local.\nDesarrollado con amor, línea por línea.",
                    color = onSurfaceSecondary,
                    fontSize = 14.sp
                )
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) {
                    Text("Cerrar", color = onSurface)
                }
            },
            containerColor = surfaceColor
        )
    }
}

@Composable
fun ChatBubble(
    message: ChatMessage,
    userBubbleColor: Color,
    userBubbleText: Color,
    promeiaTextColor: Color,
    onSurfaceSecondary: Color
) {
    val isUser = message.sender == MessageSender.USER

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (isUser) {
            // User message — rounded bubble
            Box(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(userBubbleColor)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                Column {
                    Text(
                        text = "Tú",
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        color = onSurfaceSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = message.text,
                        fontSize = 14.sp,
                        color = userBubbleText,
                        fontWeight = if (message.isPending) FontWeight.Light else FontWeight.Normal
                    )
                }
            }
        } else {
            // Kofu message — NO bubble, plain elegant text
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "Kofu",
                    fontWeight = FontWeight.Bold,
                    fontSize = 11.sp,
                    color = onSurfaceSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = message.text,
                    fontSize = 14.sp,
                    color = promeiaTextColor,
                    lineHeight = 20.sp,
                    fontWeight = if (message.isPending) FontWeight.Light else FontWeight.Normal
                )
            }
        }
    }
}
