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
    GGUF_FALLBACK("GGUF C++ (CPU/JNI Fallback)")
}

/**
 * Repositorio de alto nivel que conecta el motor nativo en C++ (libkofu_engine.so)
 * con la interfaz estética en Kotlin (Jetpack Compose).
 *
 * Toda la computación pesada, formateo de prompts, sanitización, mmap y ciclo de
 * inferencia corre exclusivamente en C++. Kotlin únicamente gestiona estados de UI
 * y transferencia de datos.
 */
object LlamaRepository {
    private const val TAG = "LlamaRepository"

    var isModelLoaded: Boolean by mutableStateOf(false)
    var currentModelName: String by mutableStateOf("")
    var activeBackend: InferenceBackend by mutableStateOf(InferenceBackend.NONE)
    var lastBenchmarkStats: String by mutableStateOf("")

    private val supportedExtensions = listOf(".litertlm", ".task", ".tflite", ".bin", ".gguf")

    // Modelo Oficial del Proyecto Kofu Mobile entrenado y optimizado para edge (ARM64)
    private const val DEFAULT_KOFU_GGUF_FILENAME = "kofu-gemma-2b-edge-q4_k_m.gguf"
    private const val DEFAULT_KOFU_GGUF_URL =
        "https://huggingface.co/shebxyd/kofu-2b-edge/resolve/main/kofu-gemma-2b-edge-q4_k_m.gguf"

    // Fallback de respaldo LiteRT
    private const val FALLBACK_LITERT_FILENAME = "Gemma-2B-It-GPU-Int4.litertlm"
    private const val FALLBACK_LITERT_URL =
        "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-gpu-int4.litertlm"

    init {
        if (KofuNativeEngine.isNativeReady()) {
            KofuNativeEngine.nativeInit()
            Log.i(TAG, "Motor nativo C++ inicializado correctamente")
        } else {
            Log.w(TAG, "Librería nativa C++ aún no cargada")
        }
    }

    /**
     * Carga un modelo en el backend nativo C++. Si la URI proviene de un ContentProvider,
     * se prepara en caché local para permitir mmap directo desde C++.
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
            val localFilePath = resolveLocalFilePath(context, modelUri, fileName)
            if (localFilePath == null) {
                withContext(Dispatchers.Main) {
                    isModelLoaded = false
                    activeBackend = InferenceBackend.NONE
                    currentModelName = "Error: No se pudo resolver la ruta local del archivo"
                }
                return@launch
            }

            // Llamada directa al motor C++ para mmap y verificación de cabecera binaria
            val loaded = KofuNativeEngine.nativeLoadModel(localFilePath)

            withContext(Dispatchers.Main) {
                if (loaded) {
                    isModelLoaded = true
                    currentModelName = KofuNativeEngine.nativeGetModelName()
                    activeBackend = when (KofuNativeEngine.nativeGetBackendType()) {
                        1 -> InferenceBackend.LITERT
                        2 -> InferenceBackend.GGUF_FALLBACK
                        else -> InferenceBackend.LITERT
                    }
                    lastBenchmarkStats = KofuNativeEngine.nativeGetStatsJson()
                    Log.i(TAG, "Modelo cargado en C++: $currentModelName | Backend: ${activeBackend.displayName}")
                } else {
                    isModelLoaded = false
                    activeBackend = InferenceBackend.NONE
                    currentModelName = "Error: Fallo al cargar en el motor C++"
                    Log.e(TAG, "Fallo nativo cargando $localFilePath")
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
            Log.e(TAG, "Error copiando content:// a caché para mmap en C++", e)
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
        return uri.lastPathSegment ?: "modelo_kofu.litertlm"
    }

    /**
     * Generación de texto transmitida token por token desde el motor C++
     * mediante callback nativo a un Flow reactivo de Kotlin.
     */
    fun generateResponseStream(prompt: String): Flow<String> = callbackFlow {
        if (!KofuNativeEngine.nativeIsModelLoaded()) {
            trySend("Error [C++ Backend]: Ningún modelo cargado. Selecciona un archivo o descarga el predeterminado.")
            close()
            return@callbackFlow
        }

        KofuNativeEngine.nativeGenerateStream(prompt) { token ->
            trySend(token)
        }

        lastBenchmarkStats = KofuNativeEngine.nativeGetStatsJson()
        close()

        awaitClose {
            KofuNativeEngine.nativeCancelGeneration()
        }
    }.flowOn(Dispatchers.IO)

    fun cancelGeneration() {
        KofuNativeEngine.nativeCancelGeneration()
    }

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

        // 1. Verificar si el modelo oficial de Kofu ya existe localmente
        val existingKofuGguf = File(externalDir, DEFAULT_KOFU_GGUF_FILENAME)
        if (existingKofuGguf.exists() && existingKofuGguf.length() > 100L * 1024L * 1024L) {
            onComplete(Uri.fromFile(existingKofuGguf))
            return
        }

        val existingLiteRt = File(externalDir, FALLBACK_LITERT_FILENAME)
        if (existingLiteRt.exists() && existingLiteRt.length() > 10L * 1024L * 1024L) {
            onComplete(Uri.fromFile(existingLiteRt))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            // Descarga primaria: Modelo propio entrenado Kofu Gemma 2B Edge
            val kofuTarget = File(externalDir, DEFAULT_KOFU_GGUF_FILENAME)
            val kofuSuccess = tryDownloadFile(client, DEFAULT_KOFU_GGUF_URL, kofuTarget, onProgress)

            if (kofuSuccess && kofuTarget.length() > 1024L) {
                withContext(Dispatchers.Main) {
                    onComplete(Uri.fromFile(kofuTarget))
                }
                return@launch
            } else {
                kofuTarget.delete()
                Log.w(TAG, "Descarga de $DEFAULT_KOFU_GGUF_URL no completada, aplicando fallback a $FALLBACK_LITERT_FILENAME...")
            }

            // Fallback secundario: LiteRT oficial
            val liteRtTarget = File(externalDir, FALLBACK_LITERT_FILENAME)
            try {
                val liteRtSuccess = tryDownloadFile(client, FALLBACK_LITERT_URL, liteRtTarget, onProgress)
                if (liteRtSuccess && liteRtTarget.length() > 1024L) {
                    withContext(Dispatchers.Main) {
                        onComplete(Uri.fromFile(liteRtTarget))
                    }
                } else {
                    liteRtTarget.delete()
                    withContext(Dispatchers.Main) {
                        onError("No se pudo descargar el modelo oficial de Kofu ni el de respaldo")
                    }
                }
            } catch (e: Exception) {
                liteRtTarget.delete()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Error de descarga")
                }
            }
        }
    }

    private suspend fun tryDownloadFile(
        client: OkHttpClient,
        url: String,
        targetFile: File,
        onProgress: (Float) -> Unit
    ): Boolean {
        return try {
            val request = Request.Builder().url(url).build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return false

                val body = response.body ?: return false
                val totalLength = body.contentLength()
                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(targetFile)

                val buffer = ByteArray(8 * 1024)
                var downloaded: Long = 0
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
            Log.w(TAG, "Excepción descargando $url: ${e.message}")
            targetFile.delete()
            false
        }
    }
}
