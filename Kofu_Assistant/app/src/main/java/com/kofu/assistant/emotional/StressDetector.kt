package com.kofu.assistant.emotional

data class StressAnalysisResult(
    val isStressDetected: Boolean,
    val score: Float,
    val detectedTriggers: List<String>
)

object StressDetector {

    private val stressKeywords = setOf(
        "ansiedad", "ansioso", "ansiosa", "abrumado", "abrumada", "abrumacion", "abrumación",
        "saturado", "saturada", "saturacion", "saturación", "estres", "estrés", "estresado", "estresada",
        "colapso", "colapsado", "colapsar", "panico", "pánico", "desesperado", "desesperada",
        "angustia", "angustiado", "angustiada", "agotado", "agotada", "sobrecargado", "sobrecargada",
        "bloqueado", "bloqueada", "frustrado", "frustrada", "limite", "límite"
    )

    private val overloadPhrases = listOf(
        "no puedo mas", "no puedo más", "ya no puedo", "no se que hacer", "no sé qué hacer",
        "demasiado trabajo", "demasiadas cosas", "ya no aguanto", "mucha presion", "mucha presión",
        "estoy al limite", "estoy al límite", "me rebaso", "me rebasó", "me ahogo"
    )

    private val excessiveExclamationRegex = Regex("[!¡]{2,}")
    private val capsRegex = Regex("\\b[A-ZÁÉÍÓÚÑ]{4,}\\b")

    /**
     * Analyzes user prompt for emotional stress markers.
     * Returns StressAnalysisResult indicating if stress threshold is reached.
     */
    fun analyze(message: String): StressAnalysisResult {
        if (message.isBlank()) {
            return StressAnalysisResult(isStressDetected = false, score = 0.0f, detectedTriggers = emptyList())
        }

        val triggers = mutableListOf<String>()
        var score = 0.0f
        val normalized = message.lowercase()

        // 1. Check overload phrases
        for (phrase in overloadPhrases) {
            if (normalized.contains(phrase)) {
                score += 0.5f
                triggers.add("Frase de sobrecarga: '$phrase'")
            }
        }

        // 2. Check keywords
        val words = normalized.split(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ]")).filter { it.isNotBlank() }
        for (word in words) {
            if (stressKeywords.contains(word)) {
                score += 0.3f
                triggers.add("Palabra clave de estrés: '$word'")
            }
        }

        // 3. Check excessive exclamation marks
        if (excessiveExclamationRegex.containsMatchIn(message)) {
            score += 0.3f
            triggers.add("Exceso de signos de exclamación")
        }

        // 4. Check ALL CAPS shouting/overload words
        val capsMatches = capsRegex.findAll(message).map { it.value }.toList()
        if (capsMatches.isNotEmpty()) {
            score += 0.2f
            triggers.add("Texto en mayúsculas: ${capsMatches.take(2).joinToString()}")
        }

        val isStress = score >= 0.3f

        return StressAnalysisResult(
            isStressDetected = isStress,
            score = score,
            detectedTriggers = triggers
        )
    }

    fun isStressDetected(message: String): Boolean {
        return analyze(message).isStressDetected
    }
}
