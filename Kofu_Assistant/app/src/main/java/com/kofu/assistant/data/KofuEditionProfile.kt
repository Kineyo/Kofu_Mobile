package com.kofu.assistant.data

import com.kofu.assistant.BuildConfig

/**
 * Perfiles de especialización por nodo tecnológico y generación Android:
 * - NODE_7NM_LEGACY (Android 5.0–7.1+, ARMv8.0-A): síntesis ultraligera y consulta factual rápida.
 * - NODE_5NM_MAINSTREAM (Android 8.0–15.0, ARMv8.2-A DotProd+FP16): ingeniería de software y código.
 * - FRONTIER_SUB5NM (Android 16+ Flagship <5nm, ARMv8.6-A/ARMv9 i8mm): razonamiento profundo y ciencia avanzada.
 */
enum class SiliconEdition(
    val code: String,
    val editionTitle: String,
    val badgeLabel: String,
    val androidRange: String,
    val aiTaskFocus: String,
    val isaTarget: String,
    val defaultModelId: String,
    val defaultMaxTokens: Int,
    val defaultTemperature: Float,
    val maxRagChunks: Int,
    val maxRagContextChars: Int,
    val maxInMemoryMessages: Int,
    val defaultSystemPrompt: String
) {
    NODE_7NM_LEGACY(
        code = "7NM_LEGACY",
        editionTitle = "Kofu 7nm Legacy",
        badgeLabel = "7nm • API 21+",
        androidRange = "Android 5.0–7.x",
        aiTaskFocus = "Síntesis Ultraligera, Triaje Rápido y Consulta Factual Offline",
        isaTarget = "ARMv8.0-A NEON Universal (Sin DotProd/i8mm)",
        defaultModelId = "kofu-2b",
        defaultMaxTokens = 384,
        defaultTemperature = 0.25f,
        maxRagChunks = 1,
        maxRagContextChars = 280,
        maxInMemoryMessages = 100,
        defaultSystemPrompt = "Eres Kofu (Edición 7nm Ultraligera). Tu especialidad es síntesis rápida, clasificación y respuestas factuales directas. Responde siempre en máximo 3 oraciones concisas en español usando el contexto verificado."
    ),
    NODE_5NM_MAINSTREAM(
        code = "5NM_MAINSTREAM",
        editionTitle = "Kofu 5nm Mainstream",
        badgeLabel = "5nm • API 26–35",
        androidRange = "Android 8.0–15.0",
        aiTaskFocus = "Ingeniería de Software, Código y Asistencia Técnica Multidisciplinaria",
        isaTarget = "ARMv8.2-A NEON + DotProd + FP16 (Kryo Prime/Perf)",
        defaultModelId = "kofu-v2",
        defaultMaxTokens = 1024,
        defaultTemperature = 0.55f,
        maxRagChunks = 2,
        maxRagContextChars = 480,
        maxInMemoryMessages = 250,
        defaultSystemPrompt = "Eres Kofu (Edición 5nm Ingeniería & Código). Tu especialidad es programación en C++20/Kotlin/Python, arquitectura de sistemas y asistencia técnica precisa en español."
    ),
    FRONTIER_SUB5NM(
        code = "FRONTIER_SUB5NM",
        editionTitle = "Kofu <5nm Frontier Flagship",
        badgeLabel = "<5nm • API 36+",
        androidRange = "Android 16+ Frontera",
        aiTaskFocus = "Razonamiento Profundo, Ciencia Avanzada y Contexto Extendido",
        isaTarget = "ARMv8.6-A / ARMv9 DotProd + FP16 + i8mm Matrix",
        defaultModelId = "kofu-gemma4",
        defaultMaxTokens = 2048,
        defaultTemperature = 0.45f,
        maxRagChunks = 3,
        maxRagContextChars = 720,
        maxInMemoryMessages = 400,
        defaultSystemPrompt = "Eres Kofu (Edición <5nm Frontier Flagship). Tu especialidad es razonamiento lógico profundo, demostraciones científicas/matemáticas y arquitectura avanzada con análisis estructurado paso a paso en español."
    );
}

object KofuEditionProfile {
    val current: SiliconEdition by lazy { fromCode(BuildConfig.SILICON_TIER) }

    fun fromCode(code: String): SiliconEdition {
        return SiliconEdition.entries.find { it.code == code } ?: SiliconEdition.NODE_5NM_MAINSTREAM
    }
}
