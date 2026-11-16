package com.kofu.assistant

import com.kofu.assistant.data.AppUpdateManager
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
}
