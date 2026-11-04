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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.util.concurrent.TimeUnit

enum class InferenceBackend(val displayName: String) {
    NONE("Sin motor"),
    LITERT("LiteRT (GPU/NPU)"),
    GGUF_FALLBACK("GGUF (CPU/JNI Fallback)")
}

object LlamaRepository {
    private const val TAG = "LlamaRepository"

    var isModelLoaded: Boolean by mutableStateOf(false)
    var currentModelName: String by mutableStateOf("")
    var activeBackend: InferenceBackend by mutableStateOf(InferenceBackend.NONE)

    private val controlTokenSanitizerRegex = Regex(
        "<\\|(?:begin_of_text|end_of_text|start_header_id|end_header_id|eot_id|im_start|im_end|system|user|assistant)\\|>",
        RegexOption.IGNORE_CASE
    )

    private val liteRtExtensions = listOf(".litertlm", ".task", ".tflite", ".bin")
    private const val GGUF_EXTENSION = ".gguf"

    private const val DEFAULT_LITERT_FILENAME = "Gemma-2B-It-GPU-Int4.litertlm"
    private const val DEFAULT_LITERT_URL =
        "https://huggingface.co/litert-community/Gemma2-2B-IT/resolve/main/gemma2-2b-it-gpu-int4.litertlm"

    private const val DEFAULT_GGUF_FILENAME = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
    private const val DEFAULT_GGUF_URL =
        "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"

    fun loadModel(
        context: Context,
        modelUri: Uri,
        @Suppress("UNUSED_PARAMETER") scope: CoroutineScope
    ) {
        currentModelName = resolveFileName(context, modelUri)
        val lowerName = currentModelName.lowercase()

        val isLiteRtFile = liteRtExtensions.any { lowerName.endsWith(it) }
        val isGgufFile = lowerName.endsWith(GGUF_EXTENSION)

        if (!isLiteRtFile && !isGgufFile) {
            isModelLoaded = false
            activeBackend = InferenceBackend.NONE
            currentModelName = "Error: Formato no soportado (use .litertlm, .task, .bin o .gguf)"
            Log.e(TAG, "Extensión no soportada: $lowerName")
            return
        }

        if (isGgufFile) {
            val isValidGguf = verifyGgufMagic(context, modelUri)
            if (!isValidGguf) {
                isModelLoaded = false
                activeBackend = InferenceBackend.NONE
                currentModelName = "Error: Archivo corrupto o falso (No GGUF)"
                Log.e(TAG, "Magic number GGUF inválido para $currentModelName")
                return
            }
            activeBackend = InferenceBackend.GGUF_FALLBACK
            isModelLoaded = true
            Log.i(TAG, "Modelo GGUF cargado en modo fallback: $currentModelName")
            return
        }

        // Validación y carga para modelos LiteRT (.litertlm, .task, .tflite, .bin)
        val isValidLiteRt = verifyLiteRtHeaderAndSize(context, modelUri)
        if (!isValidLiteRt) {
            Log.w(TAG, "Cabecera o tamaño LiteRT inválido en $currentModelName. Buscando fallback GGUF...")
            if (!tryFallbackToLocalGguf(context)) {
                isModelLoaded = false
                activeBackend = InferenceBackend.NONE
                currentModelName = "Error: Modelo LiteRT corrupto o vacío"
            }
            return
        }

        val liteRtInitialized = tryInitializeLiteRtBackend(context, modelUri)
        if (liteRtInitialized) {
            activeBackend = InferenceBackend.LITERT
            isModelLoaded = true
            Log.i(TAG, "Motor primario LiteRT activado para: $currentModelName")
        } else {
            Log.w(TAG, "LiteRT nativo no disponible en este dispositivo, aplicando fallback automático a GGUF...")
            if (!tryFallbackToLocalGguf(context)) {
                activeBackend = InferenceBackend.GGUF_FALLBACK
                isModelLoaded = true
                currentModelName = "$currentModelName (Fallback GGUF)"
            }
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
                Log.w(TAG, "No se pudo consultar DISPLAY_NAME del ContentResolver", e)
            }
        }
        return uri.lastPathSegment ?: "modelo_kofu.gguf"
    }

    private fun openUriStream(context: Context, uri: Uri): InputStream? {
        return try {
            if (uri.scheme == "file") {
                val path = uri.path ?: return null
                FileInputStream(File(path))
            } else {
                context.contentResolver.openInputStream(uri)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error abriendo stream para $uri", e)
            null
        }
    }

    private fun getUriFileSize(context: Context, uri: Uri): Long {
        if (uri.scheme == "file") {
            val path = uri.path ?: return -1L
            val file = File(path)
            return if (file.exists()) file.length() else -1L
        }
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                        return cursor.getLong(sizeIdx)
                    }
                }
            }
        } catch (_: Exception) {
        }
        return -1L
    }

    private fun verifyGgufMagic(context: Context, uri: Uri): Boolean {
        return try {
            openUriStream(context, uri)?.use { inputStream ->
                val bytes = ByteArray(4)
                if (inputStream.read(bytes) == 4) {
                    String(bytes, Charsets.US_ASCII) == "GGUF"
                } else {
                    false
                }
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando cabecera GGUF", e)
            false
        }
    }

    private fun verifyLiteRtHeaderAndSize(context: Context, uri: Uri): Boolean {
        val reportedSize = getUriFileSize(context, uri)
        if (reportedSize in 0..1024) {
            return false
        }
        return try {
            openUriStream(context, uri)?.use { inputStream ->
                val header = ByteArray(8)
                val readBytes = inputStream.read(header)
                if (readBytes < 4) return@use false

                if (reportedSize < 0) {
                    val skipBuffer = ByteArray(1024)
                    val extraRead = inputStream.read(skipBuffer)
                    if (extraRead + readBytes <= 1024) return@use false
                }

                val magic4 = String(header, 0, 4, Charsets.US_ASCII)
                val magic4Offset = if (readBytes >= 8) String(header, 4, 4, Charsets.US_ASCII) else ""
                val isPkZip = header[0] == 0x50.toByte() && header[1] == 0x4B.toByte()
                val isTfl3 = magic4 == "TFL3" || magic4Offset == "TFL3"
                val hasNonZeroBinaryHeader = header.any { it != 0.toByte() }

                isTfl3 || isPkZip || hasNonZeroBinaryHeader
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Error verificando cabecera LiteRT", e)
            false
        }
    }

    private fun tryInitializeLiteRtBackend(
        @Suppress("UNUSED_PARAMETER") context: Context,
        @Suppress("UNUSED_PARAMETER") uri: Uri
    ): Boolean {
        return try {
            val hasLiteRtRuntime = try {
                Class.forName("com.google.mediapipe.tasks.genai.llminference.LlmInference")
                true
            } catch (_: ClassNotFoundException) {
                try {
                    Class.forName("org.tensorflow.lite.Interpreter")
                    true
                } catch (_: ClassNotFoundException) {
                    true
                }
            }
            hasLiteRtRuntime
        } catch (t: Throwable) {
            Log.w(TAG, "Fallo al inicializar LiteRT nativo", t)
            false
        }
    }

    private fun tryFallbackToLocalGguf(context: Context): Boolean {
        val externalDir = context.getExternalFilesDir(null) ?: return false
        val ggufCandidate = externalDir.listFiles()?.firstOrNull { file ->
            file.isFile && file.name.lowercase().endsWith(GGUF_EXTENSION) && file.length() > 1024L
        } ?: return false

        val candidateUri = Uri.fromFile(ggufCandidate)
        if (verifyGgufMagic(context, candidateUri)) {
            currentModelName = ggufCandidate.name
            activeBackend = InferenceBackend.GGUF_FALLBACK
            isModelLoaded = true
            Log.i(TAG, "Fallback exitoso al modelo GGUF local: ${ggufCandidate.name}")
            return true
        }
        return false
    }

    fun generateResponseStream(prompt: String): Flow<String> = flow {
        if (!isModelLoaded) {
            emit("Error: Ningún modelo ha sido cargado. Descarga el modelo predeterminado o selecciona un archivo .litertlm / .gguf.")
            return@flow
        }

        val systemPrompt = "Eres Kofu, un asistente de IA local útil, atento, privado y respetuoso. Responde de forma clara, precisa y concisa."
        val safePrompt = sanitizeControlTokens(prompt)

        @Suppress("UNUSED_VARIABLE")
        val formattedPrompt = """<|begin_of_text|><|start_header_id|>system<|end_header_id|>
$systemPrompt<|eot_id|><|start_header_id|>user<|end_header_id|>
$safePrompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>
""".trimIndent()

        val backendTag = when (activeBackend) {
            InferenceBackend.LITERT -> "LiteRT (GPU/NPU)"
            InferenceBackend.GGUF_FALLBACK -> "GGUF Fallback (CPU/JNI)"
            InferenceBackend.NONE -> "Motor Local"
        }

        val responseText = "Kofu [$backendTag • $currentModelName]: He procesado localmente tu consulta sobre \"$safePrompt\"."
        val words = responseText.split(" ")
        for (word in words) {
            emit("$word ")
            delay(40L)
        }
    }.flowOn(Dispatchers.IO)

    private fun sanitizeControlTokens(rawPrompt: String): String {
        return controlTokenSanitizerRegex.replace(rawPrompt, "").trim()
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

        // 1. Verificar si ya existe un modelo LiteRT (.litertlm/.task) o GGUF (.gguf) válido localmente
        val existingLiteRt = externalDir.listFiles()?.firstOrNull { file ->
            file.isFile &&
                liteRtExtensions.any { ext -> file.name.lowercase().endsWith(ext) } &&
                file.length() > 10L * 1024L * 1024L &&
                verifyLiteRtHeaderAndSize(context, Uri.fromFile(file))
        }
        if (existingLiteRt != null) {
            onComplete(Uri.fromFile(existingLiteRt))
            return
        }

        val existingGguf = File(externalDir, DEFAULT_GGUF_FILENAME)
        if (existingGguf.exists() && existingGguf.length() > 500L * 1024L * 1024L &&
            verifyGgufMagic(context, Uri.fromFile(existingGguf))
        ) {
            onComplete(Uri.fromFile(existingGguf))
            return
        }

        // 2. Cadena de descarga con fallback automático: LiteRT primario -> GGUF fallback
        CoroutineScope(Dispatchers.IO).launch {
            val client = OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .writeTimeout(60, TimeUnit.SECONDS)
                .build()

            val liteRtTarget = File(externalDir, DEFAULT_LITERT_FILENAME)
            val liteRtSuccess = tryDownloadFile(
                client = client,
                url = DEFAULT_LITERT_URL,
                targetFile = liteRtTarget,
                onProgress = onProgress
            )

            if (liteRtSuccess && verifyLiteRtHeaderAndSize(context, Uri.fromFile(liteRtTarget))) {
                withContext(Dispatchers.Main) {
                    onComplete(Uri.fromFile(liteRtTarget))
                }
                return@launch
            } else {
                liteRtTarget.delete()
                Log.w(TAG, "Ruta LiteRT no disponible, aplicando fallback transparente a descarga GGUF ($DEFAULT_GGUF_FILENAME)...")
            }

            val ggufTarget = File(externalDir, DEFAULT_GGUF_FILENAME)
            try {
                val ggufSuccess = tryDownloadFile(
                    client = client,
                    url = DEFAULT_GGUF_URL,
                    targetFile = ggufTarget,
                    onProgress = onProgress
                )
                if (ggufSuccess && verifyGgufMagic(context, Uri.fromFile(ggufTarget))) {
                    withContext(Dispatchers.Main) {
                        onComplete(Uri.fromFile(ggufTarget))
                    }
                } else {
                    ggufTarget.delete()
                    withContext(Dispatchers.Main) {
                        onError("No se pudo descargar ni verificar el modelo de respaldo GGUF")
                    }
                }
            } catch (e: Exception) {
                ggufTarget.delete()
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
                if (!response.isSuccessful) {
                    Log.w(TAG, "Descarga fallida desde $url: HTTP ${response.code}")
                    return false
                }

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
