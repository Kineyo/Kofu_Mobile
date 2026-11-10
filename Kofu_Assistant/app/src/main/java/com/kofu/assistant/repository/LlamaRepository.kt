package com.kofu.assistant.repository

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import kotlinx.coroutines.isActive
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class InferenceBackend(val displayName: String, val badgeLabel: String) {
    NONE("Sin motor", "OFFLINE"),
    LITERT("LiteRT C++ (GPU/NPU)", "LiteRT"),
    GGUF_FALLBACK("GGUF C++ (CPU/JNI ARM64)", "GGUF ARM64"),
    OLLAMA_SERVER("Servidor Ollama (Laptop)", "OLLAMA")
}

enum class BackendMode(val displayName: String) {
    AUTO("Detección Automática (Recomendado)"),
    OLLAMA("Servidor Ollama (Laptop / Red)"),
    LOCAL_CPP("Motor Nativo C++ (ARM64 On-Device)")
}

data class TurnMessage(
    val role: String, // "user", "model", "system"
    val content: String
)

data class ModelOption(
    val id: String,
    val name: String,
    val shortName: String,
    val description: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeLabel: String,
    val isGemma4: Boolean = false,
    val isRecommended: Boolean = false
)

data class DebugMetrics(
    val tokensGenerated: Int = 0,
    val tokensPerSec: Double = 0.0,
    val latencyFirstTokenMs: Long = 0,
    val totalDurationMs: Long = 0,
    val memoryRssMb: Long = 0,
    val activeBackend: String = "Inactivo",
    val activeModel: String = "Ninguno",
    val lastRawPrompt: String = "",
    val lastRawResponse: String = ""
)

/**
 * Repositorio central de inferencia y configuración de Kofu Assistant.
 * Gestiona el motor nativo en C++ (libkofu_engine.so), la conexión con servidores Ollama,
 * los parámetros de generación y el HUD de depuración.
 */
object LlamaRepository {
    private const val TAG = "LlamaRepository"
    private const val PREFS_SETTINGS = "kofu_assistant_settings"

    var isModelLoaded: Boolean by mutableStateOf(false)
    var currentModelName: String by mutableStateOf("")
    var activeBackend: InferenceBackend by mutableStateOf(InferenceBackend.NONE)
    var lastBenchmarkStats: String by mutableStateOf("")

    // Parámetros de Inferencia Configurables
    var backendMode: BackendMode by mutableStateOf(BackendMode.AUTO)
    var ollamaHost: String by mutableStateOf("http://127.0.0.1:11434")
    var ollamaModel: String by mutableStateOf("kofu:latest")
    var isOllamaConnected: Boolean by mutableStateOf(false)
    var ollamaAvailableModels: List<String> by mutableStateOf(emptyList())

    var temperature: Float by mutableStateOf(0.7f)
    var topP: Float by mutableStateOf(0.9f)
    var repeatPenalty: Float by mutableStateOf(1.1f)
    var maxTokens: Int by mutableStateOf(2048)
    var systemPrompt: String by mutableStateOf(
        "Eres Kofu, un asistente de IA local útil, atento, privado y respetuoso. Responde de forma clara, precisa y concisa en español."
    )

    // Modo de Depuración (desactivado por defecto)
    var isDebugMode: Boolean by mutableStateOf(false)
    var debugMetrics: DebugMetrics by mutableStateOf(DebugMetrics())

    // Estado de descarga automática desde Hugging Face
    var isDownloadingModel: Boolean by mutableStateOf(false)
    var isDownloadPaused: Boolean by mutableStateOf(false)
    var downloadProgress: Float by mutableStateOf(0f)
    var downloadStatusText: String by mutableStateOf("")
    var currentDownloadingUrl: String by mutableStateOf("")
    var currentDownloadingFileName: String by mutableStateOf("")
    var currentDownloadingModelName: String by mutableStateOf("")
    var downloadTotalBytes: Long by mutableStateOf(0L)
    private var downloadJob: Job? = null

    val ollamaClient: OllamaClient = OllamaClient(ollamaHost)
    private val supportedExtensions = listOf(".litertlm", ".task", ".tflite", ".bin", ".gguf")

    // Modelos Oficiales Kofu Edge
    val AVAILABLE_MODELS = listOf(
        ModelOption(
            id = "kofu-v2",
            name = "Kofu 2.0 Edge (Gemma 2 2B)",
            shortName = "Kofu 2.0",
            description = "Modelo predeterminado optimizado para teléfonos móviles ARM64. Rápido, preciso y equilibrado en español.",
            fileName = "kofu-v2-edge-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/shebxyd/kofu-v2-edge/resolve/main/kofu-v2-edge-q4_k_m.gguf",
            sizeLabel = "~1.6 GB",
            isGemma4 = false,
            isRecommended = true
        ),
        ModelOption(
            id = "kofu-2b",
            name = "Kofu 1.0 Lite (Gemma 2B)",
            shortName = "Gemma 1 Lite",
            description = "Modelo ultraligero de baja huella de memoria para dispositivos con recursos reducidos.",
            fileName = "kofu-gemma-2b-edge-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/shebxyd/kofu-2b-edge/resolve/main/kofu-gemma-2b-edge-q4_k_m.gguf",
            sizeLabel = "~1.4 GB",
            isGemma4 = false,
            isRecommended = false
        ),
        ModelOption(
            id = "kofu-gemma4",
            name = "Kofu 4.0 Pro (Gemma 4 E4B)",
            shortName = "Gemma 4 Pro",
            description = "Modelo insignia con máxima capacidad de razonamiento lógico. Requiere teléfono de gama alta (12GB+ RAM).",
            fileName = "kofu-gemma4-edge-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/shebxyd/kofu-gemma4-edge/resolve/main/kofu-gemma4-edge-q4_k_m.gguf",
            sizeLabel = "~5.3 GB",
            isGemma4 = true,
            isRecommended = false
        )
    )

    var selectedModel: ModelOption by mutableStateOf(AVAILABLE_MODELS.first())

    init {
        if (KofuNativeEngine.isNativeReady()) {
            KofuNativeEngine.nativeInit()
            Log.i(TAG, "Motor nativo C++ v2.0 inicializado")
        } else {
            Log.w(TAG, "Librería nativa C++ aún no cargada")
        }
    }

    /**
     * Carga y sincroniza las preferencias persistentes del usuario.
     */
    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        ollamaHost = prefs.getString("ollama_host", "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434"
        ollamaModel = prefs.getString("ollama_model", "kofu:latest") ?: "kofu:latest"
        val savedMode = prefs.getString("backend_mode", BackendMode.AUTO.name)
        backendMode = runCatching { BackendMode.valueOf(savedMode ?: BackendMode.AUTO.name) }.getOrDefault(BackendMode.AUTO)
        temperature = prefs.getFloat("temperature", 0.7f)
        topP = prefs.getFloat("top_p", 0.9f)
        repeatPenalty = prefs.getFloat("repeat_penalty", 1.1f)
        maxTokens = prefs.getInt("max_tokens", 2048)
        systemPrompt = prefs.getString("system_prompt", systemPrompt) ?: systemPrompt
        isDebugMode = prefs.getBoolean("debug_mode", false)

        isDownloadPaused = prefs.getBoolean("download_paused", false)
        currentDownloadingUrl = prefs.getString("download_url", "") ?: ""
        currentDownloadingFileName = prefs.getString("download_file_name", "") ?: ""
        currentDownloadingModelName = prefs.getString("download_model_name", "") ?: ""
        downloadTotalBytes = prefs.getLong("download_total_bytes", 0L)

        // Comprobar si existe descarga parcial en disco al iniciar la aplicación
        val partial = getPartialModelFile(context, selectedModel)
        if (partial != null) {
            val expected = getExpectedMinSize(selectedModel)
            val pct = (partial.length().toFloat() / expected).coerceIn(0f, 0.99f)
            downloadProgress = pct
            isDownloadPaused = true
            downloadStatusText = "Descarga pausada (${(pct * 100).toInt()}%)"
            currentDownloadingFileName = selectedModel.fileName
            currentDownloadingModelName = selectedModel.name
            currentDownloadingUrl = selectedModel.downloadUrl
        }

        ollamaClient.updateBaseUrl(ollamaHost)
    }

    fun savePreferences(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("ollama_host", ollamaHost)
            putString("ollama_model", ollamaModel)
            putString("backend_mode", backendMode.name)
            putFloat("temperature", temperature)
            putFloat("top_p", topP)
            putFloat("repeat_penalty", repeatPenalty)
            putInt("max_tokens", maxTokens)
            putString("system_prompt", systemPrompt)
            putBoolean("debug_mode", isDebugMode)
            putBoolean("download_paused", isDownloadPaused)
            putString("download_url", currentDownloadingUrl)
            putString("download_file_name", currentDownloadingFileName)
            putString("download_model_name", currentDownloadingModelName)
            putLong("download_total_bytes", downloadTotalBytes)
            apply()
        }
        ollamaClient.updateBaseUrl(ollamaHost)
    }

    /**
     * Comprueba la conectividad con el servidor Ollama en la laptop o red local.
     */
    fun probeOllama(scope: CoroutineScope, onFinished: (Boolean) -> Unit = {}) {
        scope.launch(Dispatchers.IO) {
            val res = ollamaClient.checkHealthAndFetchModels()
            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    val models = res.getOrNull() ?: emptyList()
                    val names = models.map { it.name }
                    isOllamaConnected = true
                    ollamaAvailableModels = names

                    // Si kofu:latest existe en el servidor, seleccionarlo preferentemente
                    if (names.any { it.contains("kofu") }) {
                        ollamaModel = names.first { it.contains("kofu") }
                    } else if (names.isNotEmpty() && !names.contains(ollamaModel)) {
                        ollamaModel = names.first()
                    }

                    if (backendMode == BackendMode.AUTO || backendMode == BackendMode.OLLAMA) {
                        activeBackend = InferenceBackend.OLLAMA_SERVER
                        isModelLoaded = true
                        currentModelName = "$ollamaModel (Ollama)"
                        debugMetrics = debugMetrics.copy(
                            activeBackend = "Servidor Ollama",
                            activeModel = ollamaModel
                        )
                    }
                    onFinished(true)
                } else {
                    isOllamaConnected = false
                    if (backendMode == BackendMode.OLLAMA) {
                        isModelLoaded = false
                        activeBackend = InferenceBackend.NONE
                    }
                    onFinished(false)
                }
            }
        }
    }

    /**
     * Carga un modelo en el backend C++.
     */
    fun loadModel(
        context: Context,
        modelUri: Uri,
        scope: CoroutineScope
    ) {
        val fileName = resolveFileName(context, modelUri)
        val lower = fileName.lowercase()

        if (supportedExtensions.none { lower.endsWith(it) }) {
            isModelLoaded = false
            activeBackend = InferenceBackend.NONE
            currentModelName = "Error: Formato no soportado (.litertlm, .tflite, .bin, .gguf)"
            Log.e(TAG, "Extensión no soportada: $lower")
            return
        }

        scope.launch(Dispatchers.IO) {
            var loaded = false

            if (modelUri.scheme == "content") {
                try {
                    context.contentResolver.openFileDescriptor(modelUri, "r")?.use { pfd ->
                        val size = pfd.statSize
                        val fd = pfd.detachFd()
                        loaded = KofuNativeEngine.nativeLoadModelFd(fd, size, fileName)
                        if (!loaded) {
                            runCatching { android.os.ParcelFileDescriptor.adoptFd(fd).close() }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallo al mapear FD directo, intentando ruta alternativa...", e)
                }
            }

            if (!loaded) {
                val localFilePath = resolveLocalFilePath(context, modelUri, fileName)
                if (localFilePath != null) {
                    loaded = KofuNativeEngine.nativeLoadModel(localFilePath)
                }
            }

            withContext(Dispatchers.Main) {
                if (loaded) {
                    isModelLoaded = true
                    currentModelName = KofuNativeEngine.nativeGetModelName()
                    activeBackend = when (KofuNativeEngine.nativeGetBackendType()) {
                        1 -> InferenceBackend.LITERT
                        2 -> InferenceBackend.GGUF_FALLBACK
                        else -> InferenceBackend.GGUF_FALLBACK
                    }
                    lastBenchmarkStats = KofuNativeEngine.nativeGetStatsJson()
                    debugMetrics = debugMetrics.copy(
                        activeBackend = activeBackend.displayName,
                        activeModel = currentModelName
                    )
                    Log.i(TAG, "Modelo cargado en C++: $currentModelName | Backend: ${activeBackend.displayName}")
                } else {
                    // Si falla la carga local y Ollama está disponible, redirigir limpiamente
                    if (isOllamaConnected) {
                        activeBackend = InferenceBackend.OLLAMA_SERVER
                        isModelLoaded = true
                        currentModelName = "$ollamaModel (Ollama)"
                    } else {
                        isModelLoaded = false
                        activeBackend = InferenceBackend.NONE
                        currentModelName = "Error: Fallo al cargar en el motor C++"
                    }
                }
            }
        }
    }

    private fun resolveLocalFilePath(context: Context, uri: Uri, fileName: String): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        return try {
            val safeFileName = File(fileName).name.replace(Regex("[^a-zA-Z0-9._-]"), "_")
            val cacheFile = File(context.cacheDir, "kofu_active_model_$safeFileName")
            if (!cacheFile.canonicalPath.startsWith(context.cacheDir.canonicalPath)) {
                Log.e(TAG, "Ruta no permitida (Path Traversal)")
                return null
            }
            if (!cacheFile.exists() || cacheFile.length() < 1024L) {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cacheFile).use { output ->
                        input.copyTo(output)
                    }
                }
            }
            cacheFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Error resolviendo ruta local de archivo", e)
            null
        }
    }

    private fun resolveFileName(context: Context, uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (nameIndex >= 0) {
                            return cursor.getString(nameIndex)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo consultar DISPLAY_NAME", e)
            }
        }
        return uri.lastPathSegment ?: "modelo_kofu.gguf"
    }

    /**
     * Generación de texto multi-turno adaptativa:
     * Si el servidor Ollama está activo, genera por streaming HTTP en tiempo real.
     * Si no, utiliza el motor C++ nativo ARM64.
     */
    fun generateChatStream(history: List<TurnMessage>): Flow<String> = callbackFlow {
        val shouldUseOllama = when (backendMode) {
            BackendMode.OLLAMA -> true
            BackendMode.LOCAL_CPP -> false
            BackendMode.AUTO -> activeBackend == InferenceBackend.OLLAMA_SERVER || (!KofuNativeEngine.nativeIsModelLoaded() && isOllamaConnected)
        }

        val fullPromptRepresentation = history.joinToString("\n") { "[${it.role.uppercase()}]: ${it.content}" }
        val startTime = System.currentTimeMillis()

        if (shouldUseOllama) {
            var ttftLatency = 0L
            val accumulated = StringBuilder()

            ollamaClient.chatStream(
                history = history,
                modelName = ollamaModel,
                systemPrompt = systemPrompt,
                temperature = temperature,
                topP = topP,
                repeatPenalty = repeatPenalty,
                maxTokens = maxTokens,
                onFirstTokenLatency = { ttft ->
                    ttftLatency = ttft
                }
            ).collect { chunk ->
                if (chunk.content.isNotEmpty()) {
                    accumulated.append(chunk.content)
                    trySend(chunk.content)
                }

                if (chunk.isDone) {
                    val totalDurationMs = System.currentTimeMillis() - startTime
                    val evalSec = if (chunk.evalDurationNs > 0) chunk.evalDurationNs / 1_000_000_000.0 else (totalDurationMs / 1000.0)
                    val tps = if (evalSec > 0.0 && chunk.evalCount > 0) chunk.evalCount / evalSec else 0.0

                    debugMetrics = DebugMetrics(
                        tokensGenerated = chunk.evalCount,
                        tokensPerSec = tps,
                        latencyFirstTokenMs = ttftLatency,
                        totalDurationMs = totalDurationMs,
                        memoryRssMb = 0L,
                        activeBackend = "Servidor Ollama (${ollamaClient.getBaseUrl()})",
                        activeModel = ollamaModel,
                        lastRawPrompt = fullPromptRepresentation,
                        lastRawResponse = accumulated.toString()
                    )
                }
            }
            close()
            return@callbackFlow
        }

        // Ejecución C++ Nativa
        if (!KofuNativeEngine.nativeIsModelLoaded()) {
            trySend("Error [Kofu Engine]: No hay un modelo cargado en memoria local ni servidor Ollama conectado. Abre Ajustes para conectar a la laptop o cargar un modelo.")
            close()
            return@callbackFlow
        }

        // Preservar system prompt y limitar historial reciente a máximo 14 turnos para respetar los 2048 tokens
        val boundedHistory = if (history.size > 14) {
            val sysTurns = history.filter { it.role == "system" }
            val recentTurns = history.filter { it.role != "system" }.takeLast(12)
            sysTurns + recentTurns
        } else {
            history
        }

        val roles = boundedHistory.map { it.role }.toTypedArray()
        val messages = boundedHistory.map { it.content }.toTypedArray()
        val responseAcc = StringBuilder()
        var tokenCount = 0
        var firstTokenTime = 0L

        try {
            KofuNativeEngine.nativeGenerateStreamHistory(roles, messages) { token ->
                if (!coroutineContext.isActive) return@nativeGenerateStreamHistory
                if (tokenCount == 0) {
                    firstTokenTime = System.currentTimeMillis() - startTime
                }
                tokenCount++
                responseAcc.append(token)
                trySend(token)
            }
        } finally {
            if (!coroutineContext.isActive) {
                KofuNativeEngine.nativeCancelGeneration()
            }
        }

        val totalTime = System.currentTimeMillis() - startTime
        val seconds = totalTime / 1000.0
        var tps = if (seconds > 0) tokenCount / seconds else 0.0

        val statsJson = KofuNativeEngine.nativeGetStatsJson()
        var nativeRssMb = 0L
        try {
            val json = JSONObject(statsJson)
            nativeRssMb = json.optLong("memory_rss_mb", 0L)
            val parsedTps = json.optDouble("tokens_per_sec", 0.0)
            if (parsedTps > 0.0) tps = parsedTps
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo parsear statsJson nativo", e)
        }

        lastBenchmarkStats = statsJson
        debugMetrics = DebugMetrics(
            tokensGenerated = tokenCount,
            tokensPerSec = tps,
            latencyFirstTokenMs = firstTokenTime,
            totalDurationMs = totalTime,
            memoryRssMb = nativeRssMb,
            activeBackend = activeBackend.displayName,
            activeModel = currentModelName,
            lastRawPrompt = fullPromptRepresentation,
            lastRawResponse = responseAcc.toString()
        )

        close()

        awaitClose {
            KofuNativeEngine.nativeCancelGeneration()
        }
    }.flowOn(Dispatchers.IO)

    fun cancelGeneration() {
        KofuNativeEngine.nativeCancelGeneration()
    }

    fun getExpectedMinSize(modelOption: ModelOption): Long {
        return when (modelOption.id) {
            "kofu-2b" -> 1_200_000_000L // ~1.4 GB
            "kofu-gemma4" -> 4_500_000_000L // ~5.3 GB
            else -> 1_400_000_000L // ~1.6 GB para kofu-v2
        }
    }

    fun isModelDownloaded(context: Context, modelOption: ModelOption): Boolean {
        val externalDir = context.getExternalFilesDir(null) ?: return false
        val file = File(externalDir, modelOption.fileName)
        return file.exists() && file.length() >= getExpectedMinSize(modelOption)
    }

    fun getLocalModelFile(context: Context, modelOption: ModelOption): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val file = File(externalDir, modelOption.fileName)
        return if (file.exists() && file.length() >= getExpectedMinSize(modelOption)) file else null
    }

    fun getPartialModelFile(context: Context, modelOption: ModelOption = selectedModel): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val targetName = currentDownloadingFileName.ifBlank { modelOption.fileName }
        val file = File(externalDir, targetName)
        val expected = getExpectedMinSize(modelOption)
        return if (file.exists() && file.length() > 0 && file.length() < expected && !isModelLoaded) file else null
    }

    fun switchModel(
        context: Context,
        modelOption: ModelOption,
        scope: CoroutineScope,
        onNeedsDownload: () -> Unit
    ) {
        selectedModel = modelOption
        val file = getLocalModelFile(context, modelOption)
        if (file != null) {
            loadModel(context, Uri.fromFile(file), scope)
        } else {
            onNeedsDownload()
        }
    }

    fun downloadModel(
        context: Context,
        modelOption: ModelOption,
        onProgress: (Float) -> Unit = {},
        onComplete: (Uri) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir == null) {
            onError("Almacenamiento externo no disponible")
            return
        }

        val localFile = File(externalDir, modelOption.fileName)
        if (localFile.exists() && localFile.length() >= getExpectedMinSize(modelOption)) {
            onProgress(1f)
            onComplete(Uri.fromFile(localFile))
            loadModel(context, Uri.fromFile(localFile), CoroutineScope(Dispatchers.Main))
            return
        }

        currentDownloadingUrl = modelOption.downloadUrl
        currentDownloadingFileName = modelOption.fileName
        currentDownloadingModelName = modelOption.name
        isDownloadingModel = true
        isDownloadPaused = false

        com.kofu.assistant.service.ModelDownloadService.startDownload(
            context = context,
            url = modelOption.downloadUrl,
            fileName = modelOption.fileName,
            modelName = modelOption.name
        )
    }

    fun downloadDefaultModel(
        context: Context,
        onProgress: (Float) -> Unit = {},
        onComplete: (Uri) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir == null) {
            onError("Almacenamiento externo no disponible")
            return
        }

        val selectedFile = getLocalModelFile(context, selectedModel)
        if (selectedFile != null) {
            onComplete(Uri.fromFile(selectedFile))
            loadModel(context, Uri.fromFile(selectedFile), CoroutineScope(Dispatchers.Main))
            return
        }

        for (m in AVAILABLE_MODELS) {
            val local = getLocalModelFile(context, m)
            if (local != null) {
                selectedModel = m
                onComplete(Uri.fromFile(local))
                loadModel(context, Uri.fromFile(local), CoroutineScope(Dispatchers.Main))
                return
            }
        }

        downloadModel(context, selectedModel, onProgress, onComplete, onError)
    }

    fun pauseDownload(context: Context) {
        com.kofu.assistant.service.ModelDownloadService.pauseDownload(context)
        isDownloadingModel = false
        isDownloadPaused = true
        val percent = (downloadProgress * 100).toInt()
        downloadStatusText = "Descarga pausada ($percent%)"
        savePreferences(context)
    }

    fun resumeDownload(context: Context) {
        val modelToDownload = AVAILABLE_MODELS.find { it.fileName == currentDownloadingFileName } ?: selectedModel
        selectedModel = modelToDownload
        isDownloadingModel = true
        isDownloadPaused = false
        val url = currentDownloadingUrl.ifBlank { modelToDownload.downloadUrl }
        val fileName = currentDownloadingFileName.ifBlank { modelToDownload.fileName }
        val modelName = currentDownloadingModelName.ifBlank { modelToDownload.name }
        downloadStatusText = "Reanudando descarga..."
        com.kofu.assistant.service.ModelDownloadService.startDownload(context, url, fileName, modelName)
    }

    fun cancelDownload(context: Context? = null) {
        context?.let { ctx ->
            com.kofu.assistant.service.ModelDownloadService.cancelDownload(ctx)
            val externalDir = ctx.getExternalFilesDir(null)
            if (externalDir != null) {
                val targetName = currentDownloadingFileName.ifBlank { selectedModel.fileName }
                val partialFile = File(externalDir, targetName)
                if (partialFile.exists() && !isModelLoaded) {
                    partialFile.delete()
                }
            }
        }
        downloadJob?.cancel()
        downloadJob = null
        isDownloadingModel = false
        isDownloadPaused = false
        downloadProgress = 0f
        downloadStatusText = "Descarga cancelada"
    }

    /**
     * Inicia automáticamente la comprobación y descarga por defecto del modelo desde Hugging Face
     * mediante Foreground Service persistente si no existe un modelo local cargado previamente.
     */
    fun startAutoDownloadIfNeeded(context: Context, scope: CoroutineScope) {
        // 1. Si ya hay un modelo cargado en el motor C++, no hacer nada
        if (KofuNativeEngine.nativeIsModelLoaded()) {
            return
        }

        // 2. Comprobar si el modelo seleccionado ya existe en el almacenamiento
        val selectedFile = getLocalModelFile(context, selectedModel)
        if (selectedFile != null) {
            Log.i(TAG, "Modelo seleccionado encontrado localmente: ${selectedFile.name}. Cargando...")
            loadModel(context, Uri.fromFile(selectedFile), scope)
            return
        }

        // 3. Comprobar si existe cualquier otro modelo oficial localmente
        for (m in AVAILABLE_MODELS) {
            val local = getLocalModelFile(context, m)
            if (local != null) {
                selectedModel = m
                Log.i(TAG, "Modelo alternativo encontrado localmente: ${local.name}. Cargando...")
                loadModel(context, Uri.fromFile(local), scope)
                return
            }
        }

        // 4. Si ya está descargando o está en pausa por el usuario / red, no reiniciar automáticamente
        if (isDownloadingModel || isDownloadPaused) return

        Log.i(TAG, "Iniciando descarga en Foreground Service de ${selectedModel.name} desde Hugging Face...")
        com.kofu.assistant.service.ModelDownloadService.startDownload(
            context = context,
            url = selectedModel.downloadUrl,
            fileName = selectedModel.fileName,
            modelName = selectedModel.name
        )
    }

    fun onDownloadedModelReady(context: Context, targetFile: File) {
        Log.i(TAG, "Modelo descargado por Foreground Service: ${targetFile.name}. Cargando en C++...")
        loadModel(context, Uri.fromFile(targetFile), CoroutineScope(Dispatchers.Main))
    }

    private suspend fun tryDownloadFileWithResume(
        client: OkHttpClient,
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        return try {
            var existingLength = 0L
            if (targetFile.exists()) {
                existingLength = targetFile.length()
            }

            val requestBuilder = Request.Builder().url(url)
            if (existingLength > 0) {
                requestBuilder.header("Range", "bytes=$existingLength-")
            }

            val request = requestBuilder.build()
            client.newCall(request).execute().use { response ->
                val code = response.code
                val isResume = (code == 206)
                if (code != 200 && code != 206) {
                    if (code == 416) {
                        return true
                    }
                    return false
                }

                val body = response.body ?: return false
                val contentLength = body.contentLength()
                val totalLength = if (isResume) existingLength + contentLength else contentLength

                val append = isResume
                var downloaded = if (isResume) existingLength else 0L

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile, append)
                val buffer = ByteArray(16 * 1024)
                var read: Int
                var lastProgressTime = System.currentTimeMillis()

                outputStream.use { out ->
                    inputStream.use { inp ->
                        while (inp.read(buffer).also { read = it } != -1) {
                            out.write(buffer, 0, read)
                            downloaded += read

                            val currentTime = System.currentTimeMillis()
                            if (currentTime - lastProgressTime > 250 || downloaded == totalLength) {
                                val progress = if (totalLength > 0) downloaded.toFloat() / totalLength else 0f
                                withContext(Dispatchers.Main) {
                                    onProgress(progress)
                                }
                                lastProgressTime = currentTime
                            }
                        }
                    }
                }
                true
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error descargando con reanudación $url: ${e.message}")
            false
        }
    }
}
