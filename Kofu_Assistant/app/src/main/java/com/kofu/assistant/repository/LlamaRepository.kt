package com.kofu.assistant.repository

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

enum class InferenceBackend(val displayName: String) {
    NONE("Sin motor"),
    LITERT("LiteRT C++ (GPU/NPU)"),
    GGUF_FALLBACK("GGUF C++ (CPU/JNI ARM64)")
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

/**
 * Repositorio de alto nivel v2.0 que conecta el motor nativo en C++ (libkofu_engine.so)
 * con la interfaz Jetpack Compose.
 *
 * Características v2.0:
 * - Soporte Multi-Turno con memoria contextual para diálogos continuos.
 * - Importación True Zero-Copy usando FileDescriptor directo (evita duplicar 1.5 GB).
 * - Descargas resilientes con reanudación HTTP (Range: bytes=...).
 * - Notificaciones de estado de benchmarks nativos (t/s, memoria RSS).
 */
object LlamaRepository {
    private const val TAG = "LlamaRepository"

    var isModelLoaded: Boolean by mutableStateOf(false)
    var currentModelName: String by mutableStateOf("")
    var activeBackend: InferenceBackend by mutableStateOf(InferenceBackend.NONE)
    var lastBenchmarkStats: String by mutableStateOf("")

    private val supportedExtensions = listOf(".litertlm", ".task", ".tflite", ".bin", ".gguf")

    // Modelos Oficiales Kofu Edge
    val AVAILABLE_MODELS = listOf(
        ModelOption(
            id = "kofu-gemma4",
            name = "Kofu 4.0 Edge (Gemma 4 E4B)",
            shortName = "Gemma 4",
            description = "Última arquitectura Gemma 4 con 42 capas, alta capacidad de razonamiento lógico, código y ciberseguridad.",
            fileName = "kofu-gemma4-edge-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/shebxyd/kofu-gemma4-edge/resolve/main/kofu-gemma4-edge-q4_k_m.gguf",
            sizeLabel = "~3.2 GB",
            isGemma4 = true,
            isRecommended = true
        ),
        ModelOption(
            id = "kofu-v2",
            name = "Kofu 2.0 Edge (Gemma 2 2B)",
            shortName = "Gemma 2",
            description = "Modelo ultraligero y de respuesta inmediata basado en Gemma 2 2B.",
            fileName = "kofu-v2-edge-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/shebxyd/kofu-v2-edge/resolve/main/kofu-v2-edge-q4_k_m.gguf",
            sizeLabel = "~1.6 GB",
            isGemma4 = false,
            isRecommended = false
        )
    )

    var selectedModel: ModelOption by mutableStateOf(AVAILABLE_MODELS.first())

    // Constantes de compatibilidad
    const val DEFAULT_KOFU_V2_GGUF_FILENAME = "kofu-v2-edge-q4_k_m.gguf"
    const val DEFAULT_KOFU_V2_GGUF_URL =
        "https://huggingface.co/shebxyd/kofu-v2-edge/resolve/main/kofu-v2-edge-q4_k_m.gguf"
    const val FALLBACK_KOFU_V1_FILENAME = "kofu-gemma-2b-edge-q4_k_m.gguf"
    const val FALLBACK_KOFU_V1_URL =
        "https://huggingface.co/shebxyd/kofu-2b-edge/resolve/main/kofu-gemma-2b-edge-q4_k_m.gguf"

    init {
        if (KofuNativeEngine.isNativeReady()) {
            KofuNativeEngine.nativeInit()
            Log.i(TAG, "Motor nativo C++ v2.0 inicializado")
        } else {
            Log.w(TAG, "Librería nativa C++ aún no cargada")
        }
    }

    /**
     * Carga un modelo en el backend C++.
     * Si la URI proviene de SAF (content://), se obtiene el FileDescriptor directamente
     * para realizar mmap inmediato sin copiar el archivo a disco (True Zero-Copy).
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
                    val pfd = context.contentResolver.openFileDescriptor(modelUri, "r")
                    if (pfd != null) {
                        val fd = pfd.detachFd()
                        val size = pfd.statSize
                        loaded = KofuNativeEngine.nativeLoadModelFd(fd, size, fileName)
                        pfd.close()
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
                    Log.i(TAG, "Modelo cargado en C++: $currentModelName | Backend: ${activeBackend.displayName}")
                } else {
                    isModelLoaded = false
                    activeBackend = InferenceBackend.NONE
                    currentModelName = "Error: Fallo al cargar en el motor C++"
                    Log.e(TAG, "Fallo nativo cargando $fileName")
                }
            }
        }
    }

    private fun resolveLocalFilePath(context: Context, uri: Uri, fileName: String): String? {
        if (uri.scheme == "file") {
            return uri.path
        }
        return try {
            val cacheFile = File(context.cacheDir, "kofu_active_model_$fileName")
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
     * Generación de texto multi-turno con historial conversacional continuo.
     */
    fun generateChatStream(history: List<TurnMessage>): Flow<String> = callbackFlow {
        if (!KofuNativeEngine.nativeIsModelLoaded()) {
            trySend("Error [C++ Backend]: Ningún modelo cargado. Selecciona un archivo o descarga el predeterminado.")
            close()
            return@callbackFlow
        }

        val roles = history.map { it.role }.toTypedArray()
        val messages = history.map { it.content }.toTypedArray()

        KofuNativeEngine.nativeGenerateStreamHistory(roles, messages) { token ->
            trySend(token)
        }

        lastBenchmarkStats = KofuNativeEngine.nativeGetStatsJson()
        close()

        awaitClose {
            KofuNativeEngine.nativeCancelGeneration()
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Generación simple de un solo turno (compatibilidad)
     */
    fun generateResponseStream(prompt: String): Flow<String> =
        generateChatStream(listOf(TurnMessage("user", prompt)))

    fun cancelGeneration() {
        KofuNativeEngine.nativeCancelGeneration()
    }

    fun isModelDownloaded(context: Context, modelOption: ModelOption): Boolean {
        val externalDir = context.getExternalFilesDir(null) ?: return false
        val file = File(externalDir, modelOption.fileName)
        return file.exists() && file.length() > 50L * 1024L * 1024L
    }

    fun getLocalModelFile(context: Context, modelOption: ModelOption): File? {
        val externalDir = context.getExternalFilesDir(null) ?: return null
        val file = File(externalDir, modelOption.fileName)
        return if (file.exists() && file.length() > 50L * 1024L * 1024L) file else null
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
        onProgress: (Float) -> Unit,
        onComplete: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir == null) {
            onError("Almacenamiento externo no disponible")
            return
        }

        val localFile = File(externalDir, modelOption.fileName)
        if (localFile.exists() && localFile.length() > 50L * 1024L * 1024L) {
            onComplete(Uri.fromFile(localFile))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(180, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val success = tryDownloadFileWithResume(client, modelOption.downloadUrl, localFile, onProgress)
            if (success && localFile.length() > 1024L) {
                withContext(Dispatchers.Main) {
                    onComplete(Uri.fromFile(localFile))
                }
            } else {
                withContext(Dispatchers.Main) {
                    onError("No se pudo descargar el modelo ${modelOption.shortName}")
                }
            }
        }
    }

    /**
     * Descarga del modelo seleccionado actualmente.
     */
    fun downloadDefaultModel(
        context: Context,
        onProgress: (Float) -> Unit,
        onComplete: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir == null) {
            onError("Almacenamiento externo no disponible")
            return
        }

        // 1. Comprobar si el modelo seleccionado ya existe localmente
        val selectedFile = getLocalModelFile(context, selectedModel)
        if (selectedFile != null) {
            onComplete(Uri.fromFile(selectedFile))
            return
        }

        // 2. Comprobar si existe cualquier otro modelo oficial localmente
        for (m in AVAILABLE_MODELS) {
            val local = getLocalModelFile(context, m)
            if (local != null) {
                selectedModel = m
                onComplete(Uri.fromFile(local))
                return
            }
        }

        // 3. Descargar el modelo seleccionado (por defecto Gemma 4)
        downloadModel(context, selectedModel, onProgress, onComplete, onError)
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
                    if (code == 416) { // Range not satisfiable -> archivo ya completo
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
