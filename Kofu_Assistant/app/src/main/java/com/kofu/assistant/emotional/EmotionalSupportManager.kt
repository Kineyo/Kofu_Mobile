package com.kofu.assistant.emotional

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class EmotionalSupportManager(private val context: Context) {

    private val json = Json { ignoreUnknownKeys = true }
    private var calmDbCache: String? = null

    companion object {
        const val CALM_MODE_INSTRUCTION =
            "MODO CALMA ACTIVO: El usuario presenta signos de tensión/estrés. Usa un tono suave, pausado y empático. Prioriza las técnicas del módulo local para guiarlo a la calma sin mencionar explícitamente que detectaste su estrés."
    }

    /**
     * Reads assets/calm_db.json asynchronously in background (Dispatchers.IO).
     */
    suspend fun loadCalmDb(): String = withContext(Dispatchers.IO) {
        calmDbCache?.let { return@withContext it }

        try {
            val content = context.assets.open("calm_db.json").bufferedReader().use { it.readText() }
            calmDbCache = content
            content
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * Analyzes user message for stress markers.
     * If stress is detected, transparently injects priority CALM_MODE_INSTRUCTION and local techniques
     * into the System Prompt for Llama 3.2 1B.
     */
    suspend fun buildSystemPrompt(
        userMessage: String,
        baseSystemPrompt: String = "Eres Promeia, una asistente IA útil, atenta y respetuosa."
    ): SystemPromptBuildResult {
        val analysis = StressDetector.analyze(userMessage)

        if (!analysis.isStressDetected) {
            return SystemPromptBuildResult(
                systemPrompt = baseSystemPrompt,
                isCalmModeActive = false,
                analysis = analysis
            )
        }

        // Load calm_db.json in background
        val calmDbJson = loadCalmDb()
        val techniquesSummary = parseTechniquesSummary(calmDbJson)

        val finalPrompt = buildString {
            append(baseSystemPrompt)
            append("\n\n")
            append(CALM_MODE_INSTRUCTION)
            if (techniquesSummary.isNotBlank()) {
                append("\n\nTÉCNICAS Y PAUTAS DISPONIBLES EN MÓDULO LOCAL:\n")
                append(techniquesSummary)
            }
        }

        return SystemPromptBuildResult(
            systemPrompt = finalPrompt,
            isCalmModeActive = true,
            analysis = analysis
        )
    }

    private fun parseTechniquesSummary(rawJson: String): String {
        if (rawJson.isBlank()) return ""
        return try {
            val root = json.parseToJsonElement(rawJson).jsonObject
            val validationPhrases = root["affective_validation_phrases"]?.jsonArray?.joinToString("\n- ") { it.jsonPrimitive.content } ?: ""
            val techniques = root["calming_techniques"]?.jsonObject

            buildString {
                if (validationPhrases.isNotBlank()) {
                    append("Pautas de Validación Afectiva:\n- ").append(validationPhrases).append("\n\n")
                }
                techniques?.forEach { (key, elem) ->
                    val obj = elem.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: key
                    val desc = obj["description"]?.jsonPrimitive?.content ?: ""
                    val steps = obj["steps"]?.jsonArray?.joinToString("\n  * ") { it.jsonPrimitive.content } ?: ""
                    append("• ").append(name).append(": ").append(desc).append("\n  * ").append(steps).append("\n")
                }
            }
        } catch (e: Exception) {
            ""
        }
    }
}

data class SystemPromptBuildResult(
    val systemPrompt: String,
    val isCalmModeActive: Boolean,
    val analysis: StressAnalysisResult
)
