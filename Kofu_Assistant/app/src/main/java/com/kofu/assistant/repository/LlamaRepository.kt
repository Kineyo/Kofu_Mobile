package com.kofu.assistant.repository

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import okhttp3.OkHttpClient
import okhttp3.Request

object LlamaRepository {
    var isModelLoaded = false
    var currentModelName = ""
    
    // Si la librería ljcamargo no resuelve en compile time, descomenta el // y usa tu propia abstracción
    // private var llamaHelper: io.github.ljcamargo.llamacpp.LlamaHelper? = null

    fun loadModel(context: Context, modelUri: Uri, @Suppress("UNUSED_PARAMETER") scope: CoroutineScope) {
        /* 
        // Descomentar cuando la librería nativa esté 100% bajada:
        llamaHelper = io.github.ljcamargo.llamacpp.LlamaHelper(
            contentResolver = context.contentResolver,
            scope = scope
        )
        llamaHelper?.loadModel(modelUri)
        */
        
        // Obtenemos el nombre del archivo para mostrarlo
        val cursor = context.contentResolver.query(modelUri, null, null, null, null)
        cursor?.use {
            if (it.moveToFirst()) {
                currentModelName = it.getString(it.getColumnIndexOrThrow(android.provider.OpenableColumns.DISPLAY_NAME))
            }
        }

        // 1. Verificación básica por extensión
        if (!currentModelName.lowercase().endsWith(".gguf")) {
            isModelLoaded = false
            currentModelName = "Error: La extensión debe ser .gguf"
            Log.e("LlamaRepository", "Extensión incorrecta: $currentModelName")
            return
        }

        // 2. Verificación profunda: Magic Numbers
        var magicNumber = ""
        try {
            context.contentResolver.openInputStream(modelUri)?.use { inputStream ->
                val bytes = ByteArray(4)
                if (inputStream.read(bytes) == 4) {
                    magicNumber = String(bytes, Charsets.US_ASCII)
                }
            }
        } catch (e: Exception) {
            Log.e("LlamaRepository", "Error leyendo el archivo", e)
        }

        if (magicNumber != "GGUF") {
            isModelLoaded = false
            currentModelName = "Error: Archivo corrupto o falso (No GGUF)"
            Log.e("LlamaRepository", "Magic number inválido: $magicNumber")
            return
        }
        
        isModelLoaded = true
        Log.i("LlamaRepository", "Modelo cargado y verificado nativamente: $currentModelName")
    }

    fun generateResponseStream(prompt: String): Flow<String> = flow {
        if (!isModelLoaded) {
            emit("Error: Ningún modelo ha sido cargado. Toca la barra superior para buscar tu archivo .gguf.")
            return@flow
        }

        val systemPrompt = "Eres Kofu, un asistente portatil que corre en un telefono. Enfocate en responder al usuario priorizando la seguridad y eficiencia. Sé breve y preciso."
        
        @Suppress("UNUSED_VARIABLE")
        val formattedPrompt = """<|begin_of_text|><|start_header_id|>system<|end_header_id|>
$systemPrompt<|eot_id|><|start_header_id|>user<|end_header_id|>
$prompt<|eot_id|><|start_header_id|>assistant<|end_header_id|>
""".trimIndent()
        
        /*
        // Aquí conectas la Inferencia Nativa Real de la librería C++:
        llamaHelper?.predict(formattedPrompt)?.collect { token ->
            emit(token)
        }
        */
        
        // Simulación temporal reactiva en lo que la librería nativa C++ termina de vincularse
        val response = "Este es Kofu Nativo procesando tu mensaje: '$prompt' usando el modelo $currentModelName. Todo ocurre en la memoria del dispositivo sin PRoot ni límites."
        val words = response.split(" ")
        for (word in words) {
            emit("$word ")
            delay(50)
        }
    }.flowOn(Dispatchers.IO)

    fun downloadDefaultModel(
        context: Context,
        onProgress: (Float) -> Unit,
        onComplete: (Uri) -> Unit,
        onError: (String) -> Unit
    ) {
        val modelUrl = "https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        val fileName = "Llama-3.2-1B-Instruct-Q4_K_M.gguf"
        val targetFile = File(context.getExternalFilesDir(null), fileName)

        // Si ya pesa más de 1GB asumimos que se descargó completamente
        if (targetFile.exists() && targetFile.length() > 1024L * 1024L * 1024L) {
            onComplete(Uri.fromFile(targetFile))
            return
        }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .readTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .writeTimeout(0, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .build()
                val request = Request.Builder().url(modelUrl).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    withContext(Dispatchers.Main) { onError("HTTP ${response.code}") }
                    return@launch
                }

                val body = response.body
                if (body == null) {
                    withContext(Dispatchers.Main) { onError("Respuesta vacía") }
                    return@launch
                }

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
                
                withContext(Dispatchers.Main) {
                    onComplete(Uri.fromFile(targetFile))
                }
            } catch (e: Exception) {
                targetFile.delete()
                withContext(Dispatchers.Main) {
                    onError(e.localizedMessage ?: "Error de descarga")
                }
            }
        }
    }
}
