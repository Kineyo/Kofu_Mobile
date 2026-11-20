package com.kofu.assistant

import com.kofu.assistant.data.AppUpdateManager
import com.kofu.assistant.data.rag.HallucinationGuard
import com.kofu.assistant.data.rag.RagDocument
import com.kofu.assistant.data.rag.RagDomain
import com.kofu.assistant.data.rag.RagRetrievalResult
import com.kofu.assistant.repository.LlamaRepository
import com.kofu.assistant.repository.TurnMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KofuLogicTest {

    @Test
    fun `isTagNewer compares semantic versions accurately`() {
        assertTrue(AppUpdateManager.isTagNewer("v2.2.0", "2.1.5"))
        assertTrue(AppUpdateManager.isTagNewer("2.1.6", "2.1.5"))
        assertTrue(AppUpdateManager.isTagNewer("3.0", "2.9.9"))
        assertFalse(AppUpdateManager.isTagNewer("v2.1.5", "2.1.5"))
        assertFalse(AppUpdateManager.isTagNewer("2.1.4", "2.1.5"))
        assertFalse(AppUpdateManager.isTagNewer("v1.9.9", "2.0.0"))
    }

    @Test
    fun `filterInferenceHistory strips welcome message, errors, and leading model turns`() {
        val welcome = "Inferencia local activa con llama.cpp ARM64. Modelo listo para consultas."
        val rawHistory = listOf(
            TurnMessage("model", welcome),
            TurnMessage("model", "Saludo huérfano previo"),
            TurnMessage("user", "Hola Kofu"),
            TurnMessage("model", "Hola, ¿en qué te ayudo?"),
            TurnMessage("model", "Error de motor: timeout"),
            TurnMessage("model", "Sin respuesta del motor."),
            TurnMessage("user", "Explica punteros en C++"),
            TurnMessage("user", "   ")
        )

        val filtered = LlamaRepository.filterInferenceHistory(rawHistory, welcome)

        assertEquals(3, filtered.size)
        assertEquals("user", filtered[0].role)
        assertEquals("Hola Kofu", filtered[0].content)
        assertEquals("model", filtered[1].role)
        assertEquals("Hola, ¿en qué te ayudo?", filtered[1].content)
        assertEquals("user", filtered[2].role)
        assertEquals("Explica punteros en C++", filtered[2].content)
    }

    @Test
    fun `filterInferenceHistory returns empty when only notices exist`() {
        val welcome = "Inferencia local activa con llama.cpp ARM64. Modelo listo para consultas."
        val rawHistory = listOf(
            TurnMessage("model", welcome),
            TurnMessage("model", "Error [Kofu Engine]: Sin modelo")
        )

        val filtered = LlamaRepository.filterInferenceHistory(rawHistory, welcome)
        assertTrue(filtered.isEmpty())
    }

    @Test
    fun `getExpectedMinSize validates 100pct MIT Kaze catalog and zero Gemma dependency`() {
        val kazeBase = LlamaRepository.AVAILABLE_MODELS.first { it.id == "kaze-4.7b-a530m" }
        val auraGo = LlamaRepository.AVAILABLE_MODELS.first { it.id == "kaze-aura-go-1.1b" }
        val lfmModel = LlamaRepository.AVAILABLE_MODELS.first { it.id == "kofu-lfm25-1b" }
        val qwen3Model = LlamaRepository.AVAILABLE_MODELS.first { it.id == "kofu-qwen3-1.7b" }

        assertTrue(kazeBase.fileName.contains("kaze-4.7b-a530m"))
        assertTrue(auraGo.fileName.contains("aura-kaze-go-1.1b"))
        assertFalse(LlamaRepository.AVAILABLE_MODELS.any { it.name.contains("Gemma", ignoreCase = true) })

        assertEquals(2_200_000_000L, LlamaRepository.getExpectedMinSize(kazeBase))
        assertEquals(500_000_000L, LlamaRepository.getExpectedMinSize(auraGo))
        assertEquals(600_000_000L, LlamaRepository.getExpectedMinSize(lfmModel))
        assertEquals(950_000_000L, LlamaRepository.getExpectedMinSize(qwen3Model))
        assertEquals(15_000_000L, LlamaRepository.getExpectedMinSize(LlamaRepository.EMBEDDER_MODEL))
    }

    @Test
    fun `stripThinkTags removes empty or reasoning Qwen3 think blocks cleanly`() {
        assertEquals(
            "¡Hola! Soy Aura.",
            HallucinationGuard.stripThinkTags("<think>\n\n</think>\n\n¡Hola! Soy Aura.")
        )
        assertEquals(
            "Respuesta final",
            HallucinationGuard.stripThinkTags("<think>paso interno</think>\nRespuesta final")
        )
        assertEquals(
            "x < 5 es correcto",
            HallucinationGuard.stripThinkTags("x < 5 es correcto")
        )
    }

    @Test
    fun `prepareGroundedPrompt places user query before verified context to preserve KV prefix`() {
        val doc = RagDocument("doc1", RagDomain.AI_EDGE, "KV Q8", "kv", "Cuantización Q8_0")
        val rag = RagRetrievalResult(
            domain = RagDomain.AI_EDGE,
            matchedChunks = listOf(doc),
            formattedContext = "- KV Q8: Cuantización Q8_0",
            lookupTimeMs = 2,
            estimatedTokens = 10,
            topScore = 2.0
        )
        val grounded = HallucinationGuard.prepareGroundedPrompt(
            userQuery = "¿Cómo ahorro RAM en KV?",
            ragResult = rag,
            baseTemperature = 0.7f,
            baseSystemPrompt = "Eres Kofu."
        )
        assertTrue(grounded.augmentedUserPrompt.startsWith("¿Cómo ahorro RAM en KV?"))
        assertTrue(grounded.augmentedUserPrompt.contains("[CONTEXTO VERIFICADO"))
        assertEquals("Eres Kofu.", grounded.effectiveSystemPrompt)
    }
}
