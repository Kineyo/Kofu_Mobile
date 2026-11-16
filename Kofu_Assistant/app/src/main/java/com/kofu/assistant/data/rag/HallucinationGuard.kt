package com.kofu.assistant.data.rag

data class GroundedInferenceRequest(
    val augmentedUserPrompt: String,
    val effectiveTemperature: Float,
    val effectiveSystemPrompt: String,
    val groundedWithRag: Boolean,
    val factualCappingApplied: Boolean
)

/**
 * Capa determinista de reducción de alucinaciones y preservación de velocidad por token.
 * - Solo inyecta contexto verificado cuando la consulta lo requiere (0 overhead en chitchat).
 * - Aplica temp capping (<= 0.35f) en consultas factuales o técnicas para evitar invenciones.
 * - Detecta premisas falsas o consultas en tiempo real imposible offline.
 */
object HallucinationGuard {

    private val REALTIME_OFFLINE_PATTERNS = listOf(
        "clima hoy", "temperatura actual", "noticias de hoy", "precio del dolar",
        "en vivo", "quien gano ayer", "marcador en vivo", "bolsa hoy",
        "hora exacta", "busca en internet", "googlea", "ahora mismo"
    )

    private val FACTUAL_KEYWORDS = setOf(
        "quien", "cuando", "cuanto", "cuantos", "cuantas", "cual", "cuales",
        "formula", "valor", "constante", "velocidad", "capital", "fecha",
        "ley", "teorema", "complejidad", "codigo", "diferencia", "define",
        "significa", "ano", "edad", "altura", "distancia", "precio", "exacto", "exacta"
    )

    fun isFactualOrTechnicalQuery(query: String, domain: RagDomain): Boolean {
        if (domain != RagDomain.CHITCHAT) return true
        val tokens = DynamicDomainRouter.tokenize(query)
        return tokens.any { it in FACTUAL_KEYWORDS }
    }

    fun isRealtimeImpossibleOffline(query: String): Boolean {
        val norm = DynamicDomainRouter.normalize(query)
        return REALTIME_OFFLINE_PATTERNS.any { norm.contains(it) }
    }

    fun prepareGroundedPrompt(
        userQuery: String,
        ragResult: RagRetrievalResult,
        baseTemperature: Float,
        baseSystemPrompt: String
    ): GroundedInferenceRequest {
        val factual = isFactualOrTechnicalQuery(userQuery, ragResult.domain)
        val hasRagContext = ragResult.formattedContext.isNotBlank()
        val realtimeOffline = isRealtimeImpossibleOffline(userQuery)

        val effectiveTemp = when {
            realtimeOffline -> minOf(baseTemperature, 0.25f)
            hasRagContext -> minOf(baseTemperature, 0.35f)
            factual -> minOf(baseTemperature, 0.40f)
            else -> baseTemperature
        }

        // Mantener systemPrompt estable para maximizar KV Cache Prefix Reuse entre turnos y consultas
        val groundedSystem = baseSystemPrompt

        // Aura v5 ya incorpora su identidad offline en los pesos; evitar truncar shards en saludos/identidad simple
        val injectRagBlock = hasRagContext && (ragResult.domain != RagDomain.KOFU_SYSTEM || realtimeOffline)

        val augmentedPrompt = when {
            realtimeOffline -> buildString {
                append(userQuery)
                append("\n\nNota del sistema: Estás 100% offline sin acceso a internet; responde de frente que no puedes consultar datos en tiempo real como clima, noticias o precios de hoy.")
            }
            injectRagBlock -> buildString {
                // Inyectar RAG al final del turno para preservar el prefijo KV de sistema e historial previo
                append(userQuery)
                append("\n\n[CONTEXTO VERIFICADO - ").append(ragResult.domain.displayName).append("]: ")
                append(ragResult.formattedContext)
            }
            else -> userQuery
        }

        return GroundedInferenceRequest(
            augmentedUserPrompt = augmentedPrompt,
            effectiveTemperature = effectiveTemp,
            effectiveSystemPrompt = groundedSystem,
            groundedWithRag = injectRagBlock,
            factualCappingApplied = effectiveTemp < baseTemperature
        )
    }

    /**
     * Detecta y recorta bucles degenerados de repetición excesiva de líneas o frases.
     */
    fun sanitizeAndValidateResponse(rawResponse: String): String {
        val trimmed = rawResponse.trim()
        if (trimmed.isEmpty()) return trimmed

        val lines = trimmed.lines()
        if (lines.size <= 4) return trimmed

        val seenCounts = HashMap<String, Int>()
        val result = mutableListOf<String>()
        for (line in lines) {
            val normLine = line.trim()
            if (normLine.length >= 20) {
                val count = (seenCounts[normLine] ?: 0) + 1
                seenCounts[normLine] = count
                if (count > 2) {
                    // Cortar bucle repetitivo detectado
                    break
                }
            }
            result.add(line)
        }
        return result.joinToString("\n").trimEnd()
    }
}
