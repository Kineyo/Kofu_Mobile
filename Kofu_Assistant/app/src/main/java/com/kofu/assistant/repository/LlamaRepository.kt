package com.kofu.assistant.repository

import android.app.ActivityManager
import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.kofu.assistant.BuildConfig
import com.kofu.assistant.data.ChatCacheManager
import com.kofu.assistant.data.ChatMessageData
import com.kofu.assistant.data.ChatSession
import com.kofu.assistant.data.KofuEditionProfile
import com.kofu.assistant.data.rag.DynamicDomainRouter
import com.kofu.assistant.data.rag.HallucinationGuard
import com.kofu.assistant.data.rag.KofuBenchmark500Suite
import com.kofu.assistant.data.rag.KofuRagDatabase
import com.kofu.assistant.data.rag.RagDomain
import com.kofu.assistant.service.ModelDownloadService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

enum class InferenceBackend(val displayName: String, val badgeLabel: String) {
    NONE("Sin motor", "OFFLINE"),
    LITERT("LiteRT C++ (GPU/NPU)", "LiteRT"),
    GGUF_FALLBACK("GGUF C++ (ARMv8 Edge)", "EDGE ARMv8"),
    OLLAMA_SERVER("Servidor Ollama (Opcional)", "OLLAMA")
}

enum class BackendMode(val displayName: String) {
    LOCAL_CPP("Motor Edge 100% Offline (Recomendado)"),
    AUTO("Detección Automática (Edge + Ollama)"),
    OLLAMA("Servidor Ollama (Red Local)")
}

enum class AcceleratorPreference(val code: Int, val label: String) {
    AUTO(0, "Auto (GPU -> NPU -> CPU)"),
    NPU(1, "Priorizar NPU"),
    GPU(2, "Priorizar GPU"),
    CPU(3, "Solo CPU ARMv8")
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
    val decodeTokensPerSec: Double = 0.0,
    val latencyFirstTokenMs: Long = 0,
    val totalDurationMs: Long = 0,
    val memoryRssMb: Long = 0,
    val anonRssMb: Long = 0,
    val availableRamMb: Long = 0,
    val activeBackend: String = "Inactivo",
    val activeModel: String = "Ninguno",
    val hardwareTier: String = "NONE",
    val socModel: String = "",
    val ramGovernorState: String = "OFF",
    val activeRagDomain: String = "OFF",
    val ragLookupMs: Long = 0,
    val ragChunksCount: Int = 0,
    val storageTier: String = "UNKNOWN",
    val storageMbps: Double = 0.0,
    val pagerMode: String = "DISABLED",
    val warmUsedMb: Long = 0,
    val warmBudgetMb: Long = 0,
    val thermalGuardState: String = "NOMINAL",
    val lastRawPrompt: String = "",
    val lastRawResponse: String = ""
)

/**
 * Repositorio central de inferencia Edge 100% autónoma y offline de Kofu Assistant.
 * Orquesta el motor nativo C++20 (libkofu_engine.so), el router RAG SQLite FTS4,
 * el guardia anti-alucinaciones y las pruebas automatizadas por consola ADB.
 */
object LlamaRepository {
    private const val TAG = "LlamaRepository"
    private const val PREFS_SETTINGS = "kofu_assistant_settings"
    private const val DEFAULT_SYSTEM_PROMPT =
        "Tu nombre es Aura. Eres una asistente educativa y conversacional 100% offline en español. El usuario es una persona distinta a ti: nunca llames Aura al usuario ni digas que no tienes nombre propio."

    private val repositoryScope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    @Volatile
    private var appContext: Context? = null

    var isModelLoaded: Boolean by mutableStateOf(false)
    var currentModelName: String by mutableStateOf("")
    var activeBackend: InferenceBackend by mutableStateOf(InferenceBackend.NONE)
    var lastBenchmarkStats: String by mutableStateOf("")

    // Autonomía 100% On-Device Offline por defecto
    var backendMode: BackendMode by mutableStateOf(BackendMode.LOCAL_CPP)
    var ollamaHost: String by mutableStateOf("http://127.0.0.1:11434")
    var ollamaModel: String by mutableStateOf("kofu:latest")
    var isOllamaConnected: Boolean by mutableStateOf(false)
    var ollamaAvailableModels: List<String> by mutableStateOf(emptyList())

    // Configuración Edge, RAG y Aceleración Hardware
    var isRagEnabled: Boolean by mutableStateOf(true)
    var preferredAccelerator: AcceleratorPreference by mutableStateOf(AcceleratorPreference.AUTO)
    var activeHardwareTier: String by mutableStateOf("NONE")

    var temperature: Float by mutableStateOf(KofuEditionProfile.current.defaultTemperature)
    var topP: Float by mutableStateOf(0.9f)
    var repeatPenalty: Float by mutableStateOf(1.1f)
    var maxTokens: Int by mutableStateOf(KofuEditionProfile.effectiveMaxTokens)
    var systemPrompt: String by mutableStateOf(KofuEditionProfile.current.defaultSystemPrompt)

    // Modo de Depuración
    var isDebugMode: Boolean by mutableStateOf(false)
    var debugMetrics: DebugMetrics by mutableStateOf(DebugMetrics())
    @Volatile
    var currentPrefillStageText: String = "Buscando contexto..."

    // Estado de descarga automática desde Hugging Face
    var isDownloadingModel: Boolean by mutableStateOf(false)
    var isDownloadPaused: Boolean by mutableStateOf(false)
    var downloadProgress: Float by mutableStateOf(0f)
    var downloadStatusText: String by mutableStateOf("")
    var currentDownloadingUrl: String by mutableStateOf("")
    var currentDownloadingFileName: String by mutableStateOf("")
    var currentDownloadingModelName: String by mutableStateOf("")
    var downloadTotalBytes: Long by mutableStateOf(0L)

    val ollamaClient: OllamaClient = OllamaClient(ollamaHost)
    private val supportedExtensions = listOf(".litertlm", ".task", ".tflite", ".bin", ".gguf")

    val EMBEDDER_MODEL = ModelOption(
        id = "kofu-embed-minilm",
        name = "Aura Micro-Embedder (all-MiniLM-L6-v2 Q8_0)",
        shortName = "MiniLM Embedder",
        description = "Micro-transformer GGUF dedicado a búsqueda semántica local int8 en RAG.",
        fileName = "all-MiniLM-L6-v2-Q8_0.gguf",
        downloadUrl = "https://huggingface.co/second-state/All-MiniLM-L6-v2-Embedding-GGUF/resolve/main/all-MiniLM-L6-v2-Q8_0.gguf",
        sizeLabel = "~24 MB",
        isGemma4 = false,
        isRecommended = false
    )

    val AVAILABLE_MODELS = listOf(
        ModelOption(
            id = "kaze-4.7b-a530m",
            name = "Kaze-4.7B-A530M Base (MoE UFS • 100% MIT)",
            shortName = "Kaze 4.7B MoE",
            description = "Cerebro base 100% MIT (1 Shared + 31 Routed, 528M activos/tok). Usa paginación jerárquica Hot/Warm/Cold sobre UFS para correr en ~1.1 GB RAM.",
            fileName = "kaze-4.7b-a530m-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/shebxyd/kaze-4.7b-a530m-edge/resolve/main/kaze-4.7b-a530m-q4_k_m.gguf",
            sizeLabel = "~2.75 GB (UFS)",
            isGemma4 = false,
            isRecommended = true
        ),
        ModelOption(
            id = "kaze-aura-go-1.1b",
            name = "Aura-Kaze-Go 1.1B Podado (100% MIT • <=3 GB)",
            shortName = "Aura-Kaze-Go",
            description = "Sub-modelo exprimido desde Kaze-4.7B (5–7 expertos activos, 100% MIT) para dispositivos <=3 GB RAM o almacenamiento eMMC lento.",
            fileName = "aura-kaze-go-1.1b-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/shebxyd/aura-kaze-go-edge/resolve/main/aura-kaze-go-1.1b-q4_k_m.gguf",
            sizeLabel = "~660 MB",
            isGemma4 = false,
            isRecommended = true
        ),
        ModelOption(
            id = "kofu-lfm25-1b",
            name = "Referencia Edge (LFM2.5 1.2B)",
            shortName = "LFM2.5 1.2B",
            description = "Alternativa ligera de referencia <=3 GB RAM (~697 MB, 5.2 tok/s en Cortex-A53, sin dependencia de Gemma).",
            fileName = "LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF/resolve/main/LFM2.5-1.2B-Instruct-Q4_K_M.gguf",
            sizeLabel = "~697 MB",
            isGemma4 = false,
            isRecommended = false
        ),
        ModelOption(
            id = "kofu-qwen3-1.7b",
            name = "Referencia 4GB (Qwen3 1.7B)",
            shortName = "Qwen3 1.7B",
            description = "Alternativa de referencia para 4 GB+ RAM (~1.03 GB, sin dependencia de Gemma).",
            fileName = "Qwen3-1.7B-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/unsloth/Qwen3-1.7B-GGUF/resolve/main/Qwen3-1.7B-Q4_K_M.gguf",
            sizeLabel = "~1.03 GB",
            isGemma4 = false,
            isRecommended = false
        )
    )

    var selectedModel: ModelOption by mutableStateOf(
        AVAILABLE_MODELS.find { it.id == KofuEditionProfile.effectiveDefaultModelId } ?: AVAILABLE_MODELS.first()
    )

    init {
        if (KofuNativeEngine.isNativeReady()) {
            KofuNativeEngine.nativeInit()
            syncNativeConfig()
            Log.i(TAG, "Motor nativo C++20 Edge inicializado")
        } else {
            Log.w(TAG, "Librería nativa C++ aún no cargada")
        }
    }

    fun syncNativeConfig(
        effectiveTemp: Float = temperature,
        effectiveSystemPrompt: String = systemPrompt,
        effectiveMaxTokens: Int = maxTokens
    ) {
        if (!KofuNativeEngine.isNativeReady()) return
        KofuNativeEngine.nativeSetLowRamBudget(
            KofuEditionProfile.isStrict2GbBudgetActive,
            KofuEditionProfile.effectiveMaxRssBudgetMb,
            BuildConfig.DEBUG
        )
        KofuNativeEngine.nativeSetPreferredAccelerator(preferredAccelerator.code)
        KofuNativeEngine.nativeSetGenerationConfig(
            temperature = effectiveTemp,
            topP = topP,
            repeatPenalty = repeatPenalty,
            maxTokens = effectiveMaxTokens,
            systemPrompt = effectiveSystemPrompt
        )
    }

    fun filterInferenceHistory(turns: List<TurnMessage>, welcomeText: String): List<TurnMessage> {
        val cleaned = mutableListOf<TurnMessage>()
        var seenUser = false
        for (turn in turns) {
            val content = turn.content.trim()
            if (content.isBlank()) continue
            if (content == welcomeText ||
                content.startsWith("Error [") ||
                content.startsWith("Error de motor:") ||
                content == "Sin respuesta del motor."
            ) {
                continue
            }
            val role = turn.role.lowercase()
            if (!seenUser) {
                if (role != "user") continue
                seenUser = true
            }
            cleaned.add(TurnMessage(role = role, content = content))
        }
        return cleaned
    }

    fun loadPreferences(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        if (KofuEditionProfile.deviceTotalRamMb == 0L) {
            val am = appCtx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            if (am != null) {
                val memInfo = ActivityManager.MemoryInfo()
                am.getMemoryInfo(memInfo)
                KofuEditionProfile.configureForDeviceRam(memInfo.totalMem)
            }
        }
        val prefs = appCtx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val cachedMbps = prefs.getFloat("ufs_probed_mbps", 0f).toDouble()
        if (cachedMbps > 0.0) {
            KofuEditionProfile.configureForStorageBandwidth(cachedMbps)
        }
        ollamaHost = prefs.getString("ollama_host", "http://127.0.0.1:11434") ?: "http://127.0.0.1:11434"
        ollamaModel = prefs.getString("ollama_model", "kofu:latest") ?: "kofu:latest"
        val savedMode = prefs.getString("backend_mode", BackendMode.LOCAL_CPP.name)
        backendMode = runCatching {
            BackendMode.valueOf(savedMode ?: BackendMode.LOCAL_CPP.name)
        }.getOrDefault(BackendMode.LOCAL_CPP)

        val edition = KofuEditionProfile.current
        val defaultModelId = KofuEditionProfile.effectiveDefaultModelId
        val savedModelId = prefs.getString("selected_model_id", defaultModelId)
        if (!savedModelId.isNullOrBlank()) {
            AVAILABLE_MODELS.find { it.id == savedModelId }?.let { selectedModel = it }
        }

        isRagEnabled = prefs.getBoolean("rag_enabled", true)
        val accelCode = prefs.getInt("preferred_accelerator", AcceleratorPreference.AUTO.code)
        preferredAccelerator = AcceleratorPreference.entries.find { it.code == accelCode } ?: AcceleratorPreference.AUTO

        temperature = prefs.getFloat("temperature", edition.defaultTemperature)
        topP = prefs.getFloat("top_p", 0.9f)
        repeatPenalty = prefs.getFloat("repeat_penalty", 1.1f)
        maxTokens = prefs.getInt("max_tokens", KofuEditionProfile.effectiveMaxTokens)
        systemPrompt = prefs.getString("system_prompt", edition.defaultSystemPrompt) ?: edition.defaultSystemPrompt
        isDebugMode = prefs.getBoolean("debug_mode", false)

        isDownloadPaused = prefs.getBoolean("download_paused", false)
        currentDownloadingUrl = prefs.getString("download_url", "") ?: ""
        currentDownloadingFileName = prefs.getString("download_file_name", "") ?: ""
        currentDownloadingModelName = prefs.getString("download_model_name", "") ?: ""
        downloadTotalBytes = prefs.getLong("download_total_bytes", 0L)

        val partial = getPartialModelFile(appCtx, selectedModel)
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
        syncNativeConfig()
    }

    fun savePreferences(context: Context) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val prefs = appCtx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        prefs.edit().apply {
            putString("ollama_host", ollamaHost)
            putString("ollama_model", ollamaModel)
            putString("backend_mode", backendMode.name)
            putString("selected_model_id", selectedModel.id)
            putBoolean("rag_enabled", isRagEnabled)
            putInt("preferred_accelerator", preferredAccelerator.code)
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
        syncNativeConfig()
    }

    fun probeOllama(scope: CoroutineScope, onFinished: (Boolean) -> Unit = {}) {
        if (backendMode == BackendMode.LOCAL_CPP) {
            onFinished(false)
            return
        }
        scope.launch(Dispatchers.IO) {
            val res = ollamaClient.checkHealthAndFetchModels()
            withContext(Dispatchers.Main) {
                if (res.isSuccess) {
                    val models = res.getOrNull() ?: emptyList()
                    val names = models.map { it.name }
                    isOllamaConnected = true
                    ollamaAvailableModels = names

                    if (names.any { it.contains("kofu") }) {
                        ollamaModel = names.first { it.contains("kofu") }
                    } else if (names.isNotEmpty() && !names.contains(ollamaModel)) {
                        ollamaModel = names.first()
                    }

                    if (backendMode == BackendMode.OLLAMA || (backendMode == BackendMode.AUTO && !KofuNativeEngine.nativeIsModelLoaded())) {
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

    fun loadModel(
        context: Context,
        modelUri: Uri,
        scope: CoroutineScope = repositoryScope
    ) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val fileName = resolveFileName(appCtx, modelUri)
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
                    appCtx.contentResolver.openFileDescriptor(modelUri, "r")?.use { pfd ->
                        val size = pfd.statSize
                        val fd = pfd.detachFd()
                        loaded = KofuNativeEngine.nativeLoadModelFd(fd, size, fileName)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Fallo al mapear FD directo, intentando ruta alternativa...", e)
                }
            }

            if (!loaded) {
                val localFilePath = resolveLocalFilePath(appCtx, modelUri, fileName)
                if (localFilePath != null) {
                    loaded = KofuNativeEngine.nativeLoadModel(localFilePath)
                }
            }

            withContext(Dispatchers.Main) {
                if (loaded) {
                    isModelLoaded = true
                    currentModelName = KofuNativeEngine.nativeGetModelName()
                    activeHardwareTier = KofuNativeEngine.nativeGetActiveHardwareTier()
                    activeBackend = when (KofuNativeEngine.nativeGetBackendType()) {
                        1 -> InferenceBackend.LITERT
                        else -> InferenceBackend.GGUF_FALLBACK
                    }
                    syncNativeConfig()
                    val statsJson = KofuNativeEngine.nativeGetStatsJson()
                    lastBenchmarkStats = statsJson
                    val parsed = runCatching { JSONObject(statsJson) }.getOrNull()
                    debugMetrics = debugMetrics.copy(
                        activeBackend = "${activeBackend.displayName} [$activeHardwareTier]",
                        activeModel = currentModelName,
                        hardwareTier = activeHardwareTier,
                        socModel = parsed?.optString("soc_model", "") ?: "",
                        availableRamMb = parsed?.optLong("available_ram_mb", 0L) ?: 0L
                    )
                    Log.i(TAG, "Modelo cargado en C++ [$activeHardwareTier]: $currentModelName")
                } else {
                    if (isOllamaConnected && backendMode != BackendMode.LOCAL_CPP) {
                        activeBackend = InferenceBackend.OLLAMA_SERVER
                        isModelLoaded = true
                        currentModelName = "$ollamaModel (Ollama)"
                    } else {
                        isModelLoaded = false
                        activeBackend = InferenceBackend.NONE
                        activeHardwareTier = "NONE"
                        currentModelName = "Error: Fallo al cargar en el motor C++"
                    }
                }
            }
        }
    }

    /**
     * Carga síncrona directa por ruta de archivo (usada por consola ADB y tests de estrés).
     */
    suspend fun loadModelFromPathSync(context: Context, filePath: String): Boolean = withContext(Dispatchers.IO) {
        appContext = context.applicationContext
        if (isDownloadingModel || isDownloadPaused) {
            cancelDownload(appContext!!)
        }
        val file = File(filePath)
        if (!file.exists() || !file.canRead()) {
            Log.e(TAG, "Modelo no encontrado o sin permiso de lectura: $filePath")
            return@withContext false
        }
        KofuNativeEngine.nativeSetPreferredAccelerator(preferredAccelerator.code)
        syncNativeConfig()
        val ok = KofuNativeEngine.nativeLoadModel(file.absolutePath)
        withContext(Dispatchers.Main) {
            if (ok) {
                isModelLoaded = true
                currentModelName = KofuNativeEngine.nativeGetModelName()
                activeHardwareTier = KofuNativeEngine.nativeGetActiveHardwareTier()
                activeBackend = InferenceBackend.GGUF_FALLBACK
                syncNativeConfig()
                lastBenchmarkStats = KofuNativeEngine.nativeGetStatsJson()
            }
        }
        ok
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

            context.cacheDir.listFiles { file ->
                file.name.startsWith("kofu_active_model_") && file.name != cacheFile.name
            }?.forEach { staleFile ->
                staleFile.delete()
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

    fun generateChatStream(
        history: List<TurnMessage>,
        maxTokensOverride: Int? = null
    ): Flow<String> = callbackFlow {
        val shouldUseOllama = when (backendMode) {
            BackendMode.OLLAMA -> true
            BackendMode.LOCAL_CPP -> false
            BackendMode.AUTO -> activeBackend == InferenceBackend.OLLAMA_SERVER || (!KofuNativeEngine.nativeIsModelLoaded() && isOllamaConnected)
        }

        // Enrutamiento dinámico RAG Offline + Guardia Anti-Alucinaciones sobre la última consulta del usuario
        var effectiveHistory = history
        var effectiveTemp = temperature
        var effectiveSystem = systemPrompt
        var ragDomainLabel = "OFF"
        var ragLookupMs = 0L
        var ragChunksCount = 0

        val lastUserIdx = history.indexOfLast { it.role.equals("user", ignoreCase = true) }
        val ctx = appContext
        currentPrefillStageText = "Buscando contexto..."
        if (isRagEnabled && lastUserIdx >= 0 && ctx != null) {
            val userQuery = history[lastUserIdx].content
            val ragResult = KofuRagDatabase.getInstance(ctx).retrieveDynamicContext(userQuery)
            val grounded = HallucinationGuard.prepareGroundedPrompt(
                userQuery = userQuery,
                ragResult = ragResult,
                baseTemperature = temperature,
                baseSystemPrompt = systemPrompt
            )
            effectiveTemp = grounded.effectiveTemperature
            effectiveSystem = grounded.effectiveSystemPrompt
            ragDomainLabel = ragResult.domain.id
            ragLookupMs = ragResult.lookupTimeMs
            ragChunksCount = ragResult.matchedChunks.size

            if (grounded.augmentedUserPrompt != userQuery) {
                val mutable = history.toMutableList()
                mutable[lastUserIdx] = TurnMessage("user", grounded.augmentedUserPrompt)
                effectiveHistory = mutable
            }
        }
        currentPrefillStageText = "Preparando respuesta..."

        val effectiveMaxTokens = maxTokensOverride ?: maxTokens
        val fullPromptRepresentation = buildString {
            append("[SYSTEM | temp=").append("%.2f".format(effectiveTemp))
            append(" | RAG=").append(ragDomainLabel).append("]: ").append(effectiveSystem).append('\n')
            effectiveHistory.forEach {
                append('[').append(it.role.uppercase()).append("]: ").append(it.content).append('\n')
            }
        }.trimEnd()

        val startTime = System.currentTimeMillis()

        if (shouldUseOllama) {
            var ttftLatency = 0L
            val accumulated = StringBuilder()

            ollamaClient.chatStream(
                history = effectiveHistory,
                modelName = ollamaModel,
                systemPrompt = effectiveSystem,
                temperature = effectiveTemp,
                topP = topP,
                repeatPenalty = repeatPenalty,
                maxTokens = effectiveMaxTokens,
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
                        hardwareTier = "REMOTE",
                        activeRagDomain = ragDomainLabel,
                        ragLookupMs = ragLookupMs,
                        ragChunksCount = ragChunksCount,
                        lastRawPrompt = fullPromptRepresentation,
                        lastRawResponse = HallucinationGuard.stripThinkTags(accumulated.toString())
                    )
                }
            }
            close()
            return@callbackFlow
        }

        // Ejecución C++ Nativa 100% Offline
        if (!KofuNativeEngine.nativeIsModelLoaded()) {
            trySend("Error [Aura Engine]: No hay un modelo GGUF cargado en memoria local. Importa un modelo o espera a que finalice la descarga.")
            close()
            return@callbackFlow
        }

        syncNativeConfig(
            effectiveTemp = effectiveTemp,
            effectiveSystemPrompt = effectiveSystem,
            effectiveMaxTokens = effectiveMaxTokens
        )

        val boundedHistory = if (effectiveHistory.size > 26) {
            val sysTurns = effectiveHistory.filter { it.role == "system" }
            val recentTurns = effectiveHistory.filter { it.role != "system" }.takeLast(24)
            sysTurns + recentTurns
        } else {
            effectiveHistory
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
        val fallbackTps = if (seconds > 0) tokenCount / seconds else 0.0

        val statsJson = KofuNativeEngine.nativeGetStatsJson()
        var nativeRssMb = 0L
        var nativeAnonRssMb = 0L
        var availRamMb = 0L
        var tier = KofuNativeEngine.nativeGetActiveHardwareTier()
        var soc = ""
        var decodeTps = fallbackTps
        var govState = "OFF"
        var storageTier = "UNKNOWN"
        var storageMbps = KofuEditionProfile.storageBandwidthMbps
        var pagerMode = "DISABLED"
        var warmUsedMb = 0L
        var warmBudgetMb = 0L
        var thermalGuardState = "NOMINAL"
        try {
            val json = JSONObject(statsJson)
            nativeRssMb = json.optLong("memory_rss_mb", 0L)
            nativeAnonRssMb = json.optLong("anon_rss_mb", 0L)
            availRamMb = json.optLong("available_ram_mb", 0L)
            tier = json.optString("hardware_tier", tier)
            soc = json.optString("soc_model", "")
            govState = json.optString("ram_governor_state", "OFF")
            val parsedDecodeTps = json.optDouble("decode_tokens_per_sec", 0.0)
            val parsedTps = json.optDouble("tokens_per_sec", 0.0)
            decodeTps = when {
                parsedDecodeTps > 0.0 -> parsedDecodeTps
                parsedTps > 0.0 -> parsedTps
                else -> fallbackTps
            }
            json.optJSONObject("moe_pager")?.let { mp ->
                storageTier = mp.optString("storage_tier", storageTier)
                storageMbps = mp.optDouble("storage_mbps", storageMbps)
                pagerMode = mp.optString("pager_mode", pagerMode)
                warmUsedMb = mp.optLong("warm_used_mb", 0L)
                warmBudgetMb = mp.optLong("warm_budget_mb", 0L)
                thermalGuardState = mp.optString("thermal_guard_state", thermalGuardState)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo parsear statsJson nativo", e)
        }

        activeHardwareTier = tier
        lastBenchmarkStats = statsJson
        debugMetrics = DebugMetrics(
            tokensGenerated = tokenCount,
            tokensPerSec = decodeTps,
            decodeTokensPerSec = decodeTps,
            latencyFirstTokenMs = firstTokenTime,
            totalDurationMs = totalTime,
            memoryRssMb = nativeRssMb,
            anonRssMb = nativeAnonRssMb,
            availableRamMb = availRamMb,
            activeBackend = "${activeBackend.displayName} [$tier]",
            activeModel = currentModelName,
            hardwareTier = tier,
            socModel = soc,
            ramGovernorState = govState,
            activeRagDomain = ragDomainLabel,
            ragLookupMs = ragLookupMs,
            ragChunksCount = ragChunksCount,
            storageTier = storageTier,
            storageMbps = storageMbps,
            pagerMode = pagerMode,
            warmUsedMb = warmUsedMb,
            warmBudgetMb = warmBudgetMb,
            thermalGuardState = thermalGuardState,
            lastRawPrompt = fullPromptRepresentation,
            lastRawResponse = responseAcc.toString()
        )

        close()

        awaitClose {
            KofuNativeEngine.nativeCancelGeneration()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Ejecuta la batería automatizada de 500 preguntas en el dispositivo verificando:
     * RAG dinámico, temp capping, velocidad por token, memoria RAM y persistencia en disco.
     */
    suspend fun runAutomatedStressBenchmark(
        context: Context,
        questionCount: Int = 500,
        maxTokensPerQuestion: Int = 16,
        fullLlmAllQuestions: Boolean = false,
        onProgress: (Int, String) -> Unit = { _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val appCtx = context.applicationContext
        appContext = appCtx
        val ragDb = KofuRagDatabase.getInstance(appCtx)
        val questions = KofuBenchmark500Suite.generate500Questions().take(questionCount.coerceIn(1, 500))
        val hasModel = KofuNativeEngine.nativeIsModelLoaded()

        val sessions = ChatCacheManager.loadSessions(appCtx)
        val benchSession = ChatSession(title = "Benchmark 500 Preguntas (${if (hasModel) "On-Device LLM" else "RAG Only"})")
        sessions.add(0, benchSession)

        val reportResults = JSONArray()
        var domainHits = 0
        var totalRagMs = 0L
        var totalInferenceMs = 0L
        var totalTokensGen = 0
        var totalTtftMs = 0L
        var maxTtftMs = 0L
        var llmEvaluatedCount = 0
        var minAvailRamMb = Long.MAX_VALUE
        var maxRssMb = 0L
        var maxAnonRssMb = 0L
        var errorCount = 0

        val startAllMs = System.currentTimeMillis()
        Log.i("KofuConsole", "=== INICIANDO BENCHMARK DE ${questions.size} PREGUNTAS (ModelLoaded=$hasModel | Tier=${KofuNativeEngine.nativeGetActiveHardwareTier()} | FullLLM=$fullLlmAllQuestions) ===")

        for ((idx, q) in questions.withIndex()) {
            val ragRes = ragDb.retrieveDynamicContext(q.prompt)
            val domainOk = ragRes.domain == q.expectedDomain || q.category == "STRESS_ANTI_HALLUCINATION"
            if (domainOk) domainHits++
            totalRagMs += ragRes.lookupTimeMs

            val grounded = HallucinationGuard.prepareGroundedPrompt(
                userQuery = q.prompt,
                ragResult = ragRes,
                baseTemperature = temperature,
                baseSystemPrompt = systemPrompt
            )

            val runLlmOnThisItem = hasModel && (fullLlmAllQuestions || (idx % 20 == 0))
            val responseBuilder = StringBuilder()
            val qStart = System.currentTimeMillis()
            if (runLlmOnThisItem) {
                llmEvaluatedCount++
                try {
                    generateChatStream(
                        history = listOf(TurnMessage("user", q.prompt)),
                        maxTokensOverride = maxTokensPerQuestion
                    ).collect { tok ->
                        responseBuilder.append(tok)
                    }
                } catch (e: Exception) {
                    errorCount++
                    Log.e("KofuConsole", "Error en pregunta #${q.id}: ${e.message}", e)
                }
            } else {
                responseBuilder.append(
                    if (ragRes.formattedContext.isNotBlank()) ragRes.formattedContext
                    else "[CHITCHAT / SIN CONTEXTO EXTRA]"
                )
            }
            val qElapsed = System.currentTimeMillis() - qStart
            if (runLlmOnThisItem) {
                totalInferenceMs += qElapsed
            }

            val cleanResp = HallucinationGuard.sanitizeAndValidateResponse(responseBuilder.toString())
            if (cleanResp.isBlank() || cleanResp.startsWith("Error [")) {
                errorCount++
            }

            if (runLlmOnThisItem) {
                totalTokensGen += debugMetrics.tokensGenerated
                totalTtftMs += debugMetrics.latencyFirstTokenMs
                if (debugMetrics.latencyFirstTokenMs > maxTtftMs) {
                    maxTtftMs = debugMetrics.latencyFirstTokenMs
                }
                if (debugMetrics.availableRamMb > 0 && debugMetrics.availableRamMb < minAvailRamMb) {
                    minAvailRamMb = debugMetrics.availableRamMb
                }
                if (debugMetrics.memoryRssMb > maxRssMb) {
                    maxRssMb = debugMetrics.memoryRssMb
                }
                if (debugMetrics.anonRssMb > maxAnonRssMb) {
                    maxAnonRssMb = debugMetrics.anonRssMb
                }
            }

            benchSession.messages.add(ChatMessageData(sender = "USER", text = q.prompt))
            benchSession.messages.add(ChatMessageData(sender = "KOFU", text = cleanResp.ifBlank { "[vacío]" }))
            ChatCacheManager.compactSessionsInPlace(sessions)

            if ((idx + 1) % 25 == 0 || idx == questions.lastIndex) {
                ChatCacheManager.flushSync(appCtx, sessions)
                val tpsAvg = if (totalInferenceMs > 0) (totalTokensGen * 1000.0) / totalInferenceMs else 0.0
                val summaryLine = "Progreso ${idx + 1}/${questions.size} | DomOK=$domainHits | RAG=${totalRagMs}ms | LLMCalls=$llmEvaluatedCount | GenTok=$totalTokensGen (${"%.1f".format(tpsAvg)} t/s) | MaxRSS=${maxRssMb}MB | MinAvailRAM=${if (minAvailRamMb == Long.MAX_VALUE) 0 else minAvailRamMb}MB | Err=$errorCount"
                Log.i("KofuConsole", summaryLine)
                onProgress(idx + 1, summaryLine)
            }

            if (runLlmOnThisItem || q.isAntiHallucinationTrap || cleanResp.startsWith("Error")) {
                val item = JSONObject().apply {
                    put("id", q.id)
                    put("category", q.category)
                    put("domain", ragRes.domain.id)
                    put("llm_evaluated", runLlmOnThisItem)
                    put("rag_ms", ragRes.lookupTimeMs)
                    put("effective_temp", grounded.effectiveTemperature.toDouble())
                    put("elapsed_ms", qElapsed)
                    put("prompt", q.prompt)
                    put("response_preview", cleanResp.take(180))
                }
                reportResults.put(item)
            }
        }

        ChatCacheManager.flushSync(appCtx, sessions)
        val totalWallMs = System.currentTimeMillis() - startAllMs
        val avgTps = if (totalInferenceMs > 0) (totalTokensGen * 1000.0) / totalInferenceMs else 0.0

        val finalReport = JSONObject().apply {
            put("total_questions", questions.size)
            put("llm_evaluated_questions", llmEvaluatedCount)
            put("model_loaded", hasModel)
            put("model_name", currentModelName)
            put("hardware_tier", KofuNativeEngine.nativeGetActiveHardwareTier())
            put("domain_routing_accuracy_pct", (domainHits * 100.0) / questions.size)
            put("total_rag_lookup_ms", totalRagMs)
            put("avg_rag_lookup_ms", totalRagMs.toDouble() / questions.size)
            put("total_tokens_generated", totalTokensGen)
            put("avg_tokens_per_sec", avgTps)
            put("avg_ttft_ms", if (llmEvaluatedCount > 0) totalTtftMs.toDouble() / llmEvaluatedCount else 0.0)
            put("max_ttft_ms", maxTtftMs)
            put("max_rss_mb", maxRssMb)
            put("max_anon_rss_mb", maxAnonRssMb)
            put("min_available_ram_mb", if (minAvailRamMb == Long.MAX_VALUE) 0 else minAvailRamMb)
            put("persisted_session_messages", benchSession.messages.size)
            put("error_count", errorCount)
            put("total_wall_time_ms", totalWallMs)
            put("samples", reportResults)
        }

        val outDir = appCtx.getExternalFilesDir(null) ?: appCtx.filesDir
        val reportFile = File(outDir, "kofu_stress_500_report.json")
        reportFile.writeText(finalReport.toString(2), Charsets.UTF_8)

        Log.i("KofuConsole", "=== BENCHMARK 500 COMPLETADO EN ${totalWallMs}ms | Archivo=${reportFile.absolutePath} ===")
        Log.i("KofuConsole", finalReport.toString())
        reportFile
    }

    fun cancelGeneration() {
        if (KofuNativeEngine.isNativeReady()) {
            KofuNativeEngine.nativeCancelGeneration()
        }
    }

    private val embedderDownloadInProgress = AtomicBoolean(false)

    fun getExpectedMinSize(modelOption: ModelOption): Long {
        return when (modelOption.id) {
            "kaze-4.7b-a530m" -> 2_200_000_000L
            "kaze-aura-go-1.1b" -> 500_000_000L
            "kofu-lfm25-1b" -> 600_000_000L
            "kofu-qwen3-1.7b" -> 950_000_000L
            "kofu-embed-minilm" -> 15_000_000L
            else -> 500_000_000L
        }
    }

    /**
     * Ejecuta la sonda UFS vs eMMC sobre almacenamiento interno privado (filesDir)
     * y configura el paginador jerárquico MoE (Hot/Warm/Cold) según RAM y ancho de banda real.
     */
    fun probeUfsAndConfigurePager(context: Context) {
        if (!KofuNativeEngine.isNativeReady()) return
        val appCtx = context.applicationContext
        val prefs = appCtx.getSharedPreferences(PREFS_SETTINGS, Context.MODE_PRIVATE)
        val cachedMbps = prefs.getFloat("ufs_probed_mbps", 0f).toDouble()
        if (cachedMbps > 0.0) {
            KofuEditionProfile.configureForStorageBandwidth(cachedMbps)
        }
        val dirPath = (appCtx.getExternalFilesDir(null) ?: appCtx.filesDir)?.absolutePath ?: return
        repositoryScope.launch(Dispatchers.IO) {
            try {
                val mbps = KofuNativeEngine.nativeProbeUfsStorage(dirPath)
                if (mbps > 0.0) {
                    KofuEditionProfile.configureForStorageBandwidth(mbps)
                    prefs.edit().putFloat("ufs_probed_mbps", mbps.toFloat()).apply()
                }
                val warmBudgetMb = when {
                    KofuEditionProfile.isUltraTight3GbDevice -> 256L
                    KofuEditionProfile.isStrict2GbBudgetActive -> 512L
                    else -> 768L
                }
                KofuNativeEngine.nativeSetLowRamBudget(
                    KofuEditionProfile.isStrict2GbBudgetActive,
                    KofuEditionProfile.effectiveMaxRssBudgetMb,
                    BuildConfig.DEBUG
                )
                KofuNativeEngine.nativeConfigureMoePager(
                    warmBudgetMb = warmBudgetMb,
                    totalRamMb = KofuEditionProfile.deviceTotalRamMb,
                    forceUfs = false
                )
                Log.i(TAG, "UFS Probe (%s): %.1f MB/s | maxRss=%d MB | warmBudget=%d MB | modelo=%s".format(
                    dirPath, mbps, KofuEditionProfile.effectiveMaxRssBudgetMb, warmBudgetMb, KofuEditionProfile.effectiveDefaultModelId
                ))
            } catch (t: Throwable) {
                Log.w(TAG, "Fallo en sonda UFS MoE", t)
            }
        }
    }

    /**
     * Descarga el micro-embedder GGUF (~24 MB) en segundo plano detrás del modelo principal,
     * sin interferir con la notificación/progreso principal y con respaldo BM25 si está offline.
     */
    fun downloadEmbedderInBackgroundIfNeeded(context: Context) {
        val appCtx = context.applicationContext
        val externalDir = appCtx.getExternalFilesDir(null) ?: return
        val existing = getLocalModelFile(appCtx, EMBEDDER_MODEL)
            ?: externalDir.listFiles { f ->
                f.isFile && f.name.endsWith(".gguf", ignoreCase = true) &&
                    (f.name.contains("minilm", ignoreCase = true) || f.name.contains("embed", ignoreCase = true)) &&
                    f.length() >= getExpectedMinSize(EMBEDDER_MODEL)
            }?.firstOrNull()
        if (existing != null) {
            ensureEmbedderReady(appCtx)
            return
        }
        if (isDownloadingModel || isDownloadPaused) return
        if (!embedderDownloadInProgress.compareAndSet(false, true)) return

        repositoryScope.launch(Dispatchers.IO) {
            try {
                val targetFile = File(externalDir, EMBEDDER_MODEL.fileName)
                val partFile = File(externalDir, "${EMBEDDER_MODEL.fileName}.part")
                if (!targetFile.canonicalPath.startsWith(externalDir.canonicalPath) ||
                    !partFile.canonicalPath.startsWith(externalDir.canonicalPath)
                ) {
                    return@launch
                }

                val client = OkHttpClient.Builder()
                    .connectTimeout(20, TimeUnit.SECONDS)
                    .readTimeout(90, TimeUnit.SECONDS)
                    .followRedirects(true)
                    .followSslRedirects(true)
                    .build()

                val existingLen = if (partFile.exists()) partFile.length() else 0L
                val reqBuilder = Request.Builder().url(EMBEDDER_MODEL.downloadUrl)
                if (existingLen > 0L) {
                    reqBuilder.header("Range", "bytes=$existingLen-")
                }

                client.newCall(reqBuilder.build()).execute().use { resp ->
                    val code = resp.code
                    if (code != 200 && code != 206) {
                        if (code == 416) partFile.delete()
                        return@launch
                    }
                    val body = resp.body ?: return@launch
                    val isResume = (code == 206)
                    val buffer = ByteArray(16 * 1024)
                    FileOutputStream(partFile, isResume).use { out ->
                        body.byteStream().use { inp ->
                            while (true) {
                                val r = inp.read(buffer)
                                if (r == -1) break
                                out.write(buffer, 0, r)
                            }
                        }
                    }
                }

                val minSize = getExpectedMinSize(EMBEDDER_MODEL)
                if (partFile.exists() && partFile.length() >= minSize && hasValidGgufHeader(partFile)) {
                    if (targetFile.exists()) targetFile.delete()
                    if (!partFile.renameTo(targetFile)) {
                        partFile.inputStream().use { inp ->
                            FileOutputStream(targetFile).use { out -> inp.copyTo(out) }
                        }
                        partFile.delete()
                    }
                    Log.i(TAG, "Micro-embedder RAG descargado detrás del modelo principal (${targetFile.length() / (1024 * 1024)} MB)")
                    ensureEmbedderReady(appCtx)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Descarga secundaria de micro-embedder omitida/offline (respaldo BM25 activo): ${e.message}")
            } finally {
                embedderDownloadInProgress.set(false)
            }
        }
    }

    private fun hasValidGgufHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 16L) return false
        val header = ByteArray(4)
        val n = FileInputStream(file).use { it.read(header) }
        return n == 4 &&
            header[0] == 'G'.code.toByte() &&
            header[1] == 'G'.code.toByte() &&
            header[2] == 'U'.code.toByte() &&
            header[3] == 'F'.code.toByte()
    }

    fun ensureEmbedderReady(context: Context) {
        if (!KofuNativeEngine.isNativeReady()) return
        val appCtx = context.applicationContext
        val externalDir = appCtx.getExternalFilesDir(null) ?: return
        val embedFile = getLocalModelFile(appCtx, EMBEDDER_MODEL)
            ?: externalDir.listFiles { f ->
                f.isFile && f.name.endsWith(".gguf", ignoreCase = true) &&
                    (f.name.contains("minilm", ignoreCase = true) || f.name.contains("embed", ignoreCase = true)) &&
                    f.length() >= getExpectedMinSize(EMBEDDER_MODEL)
            }?.firstOrNull()
            ?: return

        if (!KofuNativeEngine.nativeIsEmbedModelLoaded()) {
            if (KofuNativeEngine.nativeLoadEmbedModel(embedFile.absolutePath)) {
                KofuRagDatabase.getInstance(appCtx).syncSemanticEmbeddings()
            }
        } else {
            KofuRagDatabase.getInstance(appCtx).syncSemanticEmbeddings()
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

    fun getDownloadedModelSizeLabel(context: Context, modelOption: ModelOption): String? {
        val file = getLocalModelFile(context, modelOption) ?: return null
        val gb = file.length() / (1024.0 * 1024.0 * 1024.0)
        return if (gb >= 1.0) "%.2f GB".format(gb) else "${file.length() / (1024 * 1024)} MB"
    }

    fun getPartialModelFile(context: Context, modelOption: ModelOption = selectedModel): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val targetName = currentDownloadingFileName.ifBlank { modelOption.fileName }
        val partFile = File(externalDir, "$targetName.part")
        if (partFile.exists() && partFile.length() > 0 && !isModelLoaded) {
            return partFile
        }
        val legacyFile = File(externalDir, targetName)
        val expected = getExpectedMinSize(modelOption)
        return if (legacyFile.exists() && legacyFile.length() in 1 until expected && !isModelLoaded) {
            legacyFile
        } else {
            null
        }
    }

    fun deleteDownloadedModel(context: Context, modelOption: ModelOption): Boolean {
        val externalDir = context.getExternalFilesDir(null) ?: return false
        if (currentModelName == modelOption.fileName && KofuNativeEngine.isNativeReady()) {
            KofuNativeEngine.nativeUnloadModel()
            if (isOllamaConnected && backendMode != BackendMode.LOCAL_CPP) {
                activeBackend = InferenceBackend.OLLAMA_SERVER
                isModelLoaded = true
                currentModelName = "$ollamaModel (Ollama)"
            } else {
                isModelLoaded = false
                activeBackend = InferenceBackend.NONE
                activeHardwareTier = "NONE"
                currentModelName = ""
            }
        }
        val finalFile = File(externalDir, modelOption.fileName)
        val partFile = File(externalDir, "${modelOption.fileName}.part")
        var deleted = false
        if (finalFile.exists()) deleted = finalFile.delete() || deleted
        if (partFile.exists()) deleted = partFile.delete() || deleted
        return deleted
    }

    fun switchModel(
        context: Context,
        modelOption: ModelOption,
        scope: CoroutineScope = repositoryScope,
        onNeedsDownload: () -> Unit
    ) {
        selectedModel = modelOption
        savePreferences(context)
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
            loadModel(context, Uri.fromFile(localFile), repositoryScope)
            return
        }

        currentDownloadingUrl = modelOption.downloadUrl
        currentDownloadingFileName = modelOption.fileName
        currentDownloadingModelName = modelOption.name
        isDownloadingModel = true
        isDownloadPaused = false

        ModelDownloadService.startDownload(
            context = context,
            url = modelOption.downloadUrl,
            fileName = modelOption.fileName,
            modelName = modelOption.name
        )
    }

    fun pauseDownload(context: Context) {
        ModelDownloadService.pauseDownload(context)
        isDownloadingModel = false
        isDownloadPaused = true
        val percent = (downloadProgress * 100).toInt()
        downloadStatusText = "Descarga pausada ($percent%)"
        savePreferences(context)
    }

    fun resumeDownload(context: Context) {
        val modelToDownload = AVAILABLE_MODELS.find { it.fileName == currentDownloadingFileName } ?: selectedModel
        selectedModel = modelToDownload
        savePreferences(context)
        isDownloadingModel = true
        isDownloadPaused = false
        val url = currentDownloadingUrl.ifBlank { modelToDownload.downloadUrl }
        val fileName = currentDownloadingFileName.ifBlank { modelToDownload.fileName }
        val modelName = currentDownloadingModelName.ifBlank { modelToDownload.name }
        downloadStatusText = "Reanudando descarga..."
        ModelDownloadService.startDownload(context, url, fileName, modelName)
    }

    fun cancelDownload(context: Context? = null) {
        context?.let { ctx ->
            ModelDownloadService.cancelDownload(ctx)
            val externalDir = ctx.getExternalFilesDir(null)
            if (externalDir != null) {
                val targetName = currentDownloadingFileName.ifBlank { selectedModel.fileName }
                val partFile = File(externalDir, "$targetName.part")
                if (partFile.exists()) partFile.delete()

                val expected = getExpectedMinSize(
                    AVAILABLE_MODELS.find { it.fileName == targetName } ?: selectedModel
                )
                val legacyPartial = File(externalDir, targetName)
                if (legacyPartial.exists() && legacyPartial.length() < expected && !isModelLoaded) {
                    legacyPartial.delete()
                }
            }
        }
        isDownloadingModel = false
        isDownloadPaused = false
        downloadProgress = 0f
        downloadStatusText = "Descarga cancelada"
    }

    fun startAutoDownloadIfNeeded(context: Context, scope: CoroutineScope = repositoryScope) {
        appContext = context.applicationContext
        val selectedFile = getLocalModelFile(context, selectedModel)
        if (selectedFile != null) {
            if (!KofuNativeEngine.nativeIsModelLoaded() || currentModelName != selectedFile.name) {
                Log.i(TAG, "Modelo seleccionado encontrado localmente: ${selectedFile.name}. Cargando...")
                loadModel(context, Uri.fromFile(selectedFile), scope)
            }
            downloadEmbedderInBackgroundIfNeeded(context)
            return
        }

        if (KofuNativeEngine.nativeIsModelLoaded()) {
            downloadEmbedderInBackgroundIfNeeded(context)
            return
        }

        // Comprobar si existe cualquier modelo GGUF oficial o importado localmente en externalFilesDir
        for (m in AVAILABLE_MODELS) {
            val local = getLocalModelFile(context, m)
            if (local != null) {
                selectedModel = m
                savePreferences(context)
                Log.i(TAG, "Modelo alternativo encontrado localmente: ${local.name}. Cargando...")
                loadModel(context, Uri.fromFile(local), scope)
                downloadEmbedderInBackgroundIfNeeded(context)
                return
            }
        }

        val anyLocalGguf = context.getExternalFilesDir(null)
            ?.listFiles { f ->
                f.isFile &&
                    f.name.endsWith(".gguf", ignoreCase = true) &&
                    !f.name.equals(EMBEDDER_MODEL.fileName, ignoreCase = true) &&
                    !f.name.contains("minilm", ignoreCase = true) &&
                    f.length() > 200_000_000L
            }
            ?.firstOrNull()
        if (anyLocalGguf != null) {
            Log.i(TAG, "Modelo local GGUF detectado: ${anyLocalGguf.name}. Cargando offline...")
            loadModel(context, Uri.fromFile(anyLocalGguf), scope)
            downloadEmbedderInBackgroundIfNeeded(context)
            return
        }

        if (isDownloadingModel || isDownloadPaused) return

        Log.i(TAG, "Iniciando descarga en Foreground Service de ${selectedModel.name}...")
        ModelDownloadService.startDownload(
            context = context,
            url = selectedModel.downloadUrl,
            fileName = selectedModel.fileName,
            modelName = selectedModel.name
        )
    }

    fun onDownloadedModelReady(context: Context, targetFile: File) {
        Log.i(TAG, "Modelo descargado por Foreground Service: ${targetFile.name}. Cargando en C++...")
        loadModel(context, Uri.fromFile(targetFile), repositoryScope)
        downloadEmbedderInBackgroundIfNeeded(context)
    }
}
