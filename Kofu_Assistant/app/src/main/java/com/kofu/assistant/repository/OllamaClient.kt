package com.kofu.assistant.repository

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

data class OllamaResponseChunk(
    val content: String,
    val isDone: Boolean = false,
    val evalCount: Int = 0,
    val evalDurationNs: Long = 0,
    val promptEvalDurationNs: Long = 0,
    val totalDurationNs: Long = 0
)

data class OllamaModelInfo(
    val name: String,
    val parameterSize: String,
    val quantization: String,
    val sizeBytes: Long
)

/**
 * Cliente de red optimizado para interactuar con servidores de Ollama (locales o remotos).
 * Soporta streaming continuo vía NDJSON (/api/chat), descubrimiento de modelos y métricas de rendimiento.
 */
class OllamaClient(
    private var baseUrl: String = "http://127.0.0.1:11434"
) {
    companion object {
        private const val TAG = "OllamaClient"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    fun updateBaseUrl(newUrl: String) {
        baseUrl = newUrl.trimEnd('/')
    }

    fun getBaseUrl(): String = baseUrl

    /**
     * Comprueba conectividad y recupera los modelos disponibles en el servidor Ollama.
     */
    suspend fun checkHealthAndFetchModels(): Result<List<OllamaModelInfo>> = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url("$baseUrl/api/tags")
                .get()
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("HTTP ${response.code}: ${response.message}"))
                }

                val body = response.body?.string() ?: return@withContext Result.failure(Exception("Respuesta vacía"))
                val json = JSONObject(body)
                val modelsArray = json.optJSONArray("models") ?: JSONArray()
                val list = mutableListOf<OllamaModelInfo>()

                for (i in 0 until modelsArray.length()) {
                    val m = modelsArray.getJSONObject(i)
                    val name = m.optString("name", "desconocido")
                    val size = m.optLong("size", 0L)
                    val details = m.optJSONObject("details")
                    val paramSize = details?.optString("parameter_size", "") ?: ""
                    val quant = details?.optString("quantization_level", "") ?: ""
                    list.add(OllamaModelInfo(name, paramSize, quant, size))
                }
                Result.success(list)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al conectar con Ollama en $baseUrl: ${e.message}")
            Result.failure(e)
        }
    }

    /**
     * Generación de respuestas vía streaming multi-turno con soporte para parámetros de inferencia.
     */
    fun chatStream(
        history: List<TurnMessage>,
        modelName: String,
        systemPrompt: String = "",
        temperature: Float = 0.7f,
        topP: Float = 0.9f,
        repeatPenalty: Float = 1.1f,
        maxTokens: Int = 2048,
        onFirstTokenLatency: (Long) -> Unit = {}
    ): Flow<OllamaResponseChunk> = callbackFlow {
        val rootJson = JSONObject()
        rootJson.put("model", modelName)
        rootJson.put("stream", true)

        val options = JSONObject()
        options.put("temperature", temperature.toDouble())
        options.put("top_p", topP.toDouble())
        options.put("repeat_penalty", repeatPenalty.toDouble())
        options.put("num_predict", maxTokens)
        rootJson.put("options", options)

        val messagesArray = JSONArray()
        if (systemPrompt.isNotBlank()) {
            val sysObj = JSONObject()
            sysObj.put("role", "system")
            sysObj.put("content", systemPrompt)
            messagesArray.put(sysObj)
        }

        for (turn in history) {
            val role = when (turn.role.lowercase()) {
                "user" -> "user"
                "assistant", "model" -> "assistant"
                "system" -> "system"
                else -> "user"
            }
            val msgObj = JSONObject()
            msgObj.put("role", role)
            msgObj.put("content", turn.content)
            messagesArray.put(msgObj)
        }
        rootJson.put("messages", messagesArray)

        val requestBody = rootJson.toString().toRequestBody(JSON_MEDIA_TYPE)
        val request = Request.Builder()
            .url("$baseUrl/api/chat")
            .post(requestBody)
            .build()

        val call = client.newCall(request)
        val startTime = System.currentTimeMillis()
        var hasEmittedFirstToken = false

        try {
            val response = call.execute()
            if (!response.isSuccessful) {
                trySend(
                    OllamaResponseChunk(
                        content = "Error de Ollama [HTTP ${response.code}]: Asegúrate de que el modelo '$modelName' esté descargado en el servidor.",
                        isDone = true
                    )
                )
                close()
                return@callbackFlow
            }

            val body = response.body
            if (body == null) {
                trySend(OllamaResponseChunk("Error: Cuerpo de respuesta vacío", isDone = true))
                close()
                return@callbackFlow
            }

            val reader = BufferedReader(InputStreamReader(body.byteStream()))
            var line: String?

            while (reader.readLine().also { line = it } != null) {
                val currentLine = line?.trim() ?: continue
                if (currentLine.isBlank()) continue

                val chunkJson = JSONObject(currentLine)
                val msg = chunkJson.optJSONObject("message")
                val textChunk = msg?.optString("content", "") ?: ""
                val isDone = chunkJson.optBoolean("done", false)

                if (!hasEmittedFirstToken && textChunk.isNotEmpty()) {
                    hasEmittedFirstToken = true
                    val ttft = System.currentTimeMillis() - startTime
                    onFirstTokenLatency(ttft)
                }

                val evalCount = chunkJson.optInt("eval_count", 0)
                val evalDuration = chunkJson.optLong("eval_duration", 0L)
                val promptDuration = chunkJson.optLong("prompt_eval_duration", 0L)
                val totalDuration = chunkJson.optLong("total_duration", 0L)

                trySend(
                    OllamaResponseChunk(
                        content = textChunk,
                        isDone = isDone,
                        evalCount = evalCount,
                        evalDurationNs = evalDuration,
                        promptEvalDurationNs = promptDuration,
                        totalDurationNs = totalDuration
                    )
                )

                if (isDone) break
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error durante streaming de Ollama", e)
            trySend(OllamaResponseChunk("Error de conexión con Ollama: ${e.localizedMessage}", isDone = true))
        } finally {
            close()
        }

        awaitClose {
            call.cancel()
        }
    }.flowOn(Dispatchers.IO)
}
