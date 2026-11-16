package com.kofu.assistant.data

import com.kofu.assistant.BuildConfig

/**
 * Perfiles de especialización por nodo tecnológico y generación Android:
 * - NODE_7NM_LEGACY (Android 5.0–7.1+, ARMv8.0-A): síntesis ultraligera y consulta factual rápida.
 * - NODE_5NM_MAINSTREAM (Android 8.0–15.0, ARMv8.2-A DotProd+FP16): ingeniería de software y código.
 * - FRONTIER_SUB5NM (Android 16+ Flagship <5nm, ARMv8.6-A/ARMv9 i8mm): razonamiento profundo y ciencia avanzada.
 *
 * En la rama kofu-go se aplica además un presupuesto estricto <= 2 GB (modelo + KV + RAG + ART)
 * para dispositivos de gama media/entrada (<= 6 GB RAM física) evitando terminaciones por lmkd.
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
        editionTitle = "Aura (7nm Edge)",
        badgeLabel = "7nm • <=2GB Guard",
        androidRange = "Android 5.0–7.x",
        aiTaskFocus = "Gemma 3 1B 4-bit, RAG Escolar y Respuesta Rápida Offline",
        isaTarget = "ARMv8.0-A NEON Universal (Sin DotProd/i8mm)",
        defaultModelId = "kofu-go-1b",
        defaultMaxTokens = 256,
        defaultTemperature = 0.50f,
        maxRagChunks = 1,
        maxRagContextChars = 180,
        maxInMemoryMessages = 100,
        defaultSystemPrompt = "Eres Aura, asistente educativa y conversacional 100% offline en español. Nunca necesitas internet ni servidores externos."
    ),
    NODE_5NM_MAINSTREAM(
        code = "5NM_MAINSTREAM",
        editionTitle = "Aura (5nm Edge)",
        badgeLabel = "5nm • API 26–35",
        androidRange = "Android 8.0–15.0",
        aiTaskFocus = "Programación, Matemáticas, Español y Asistencia Escolar Offline",
        isaTarget = "ARMv8.2-A NEON + DotProd + FP16 (Kryo Prime/Perf)",
        defaultModelId = "kofu-go-1b",
        defaultMaxTokens = 640,
        defaultTemperature = 0.35f,
        maxRagChunks = 2,
        maxRagContextChars = 360,
        maxInMemoryMessages = 150,
        defaultSystemPrompt = "Eres Aura, asistente educativa local experta en programación, matemáticas, español y ciencias. Responde con claridad en español."
    ),
    FRONTIER_SUB5NM(
        code = "FRONTIER_SUB5NM",
        editionTitle = "Aura Frontier (<5nm)",
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
        defaultSystemPrompt = "Eres Aura (Edición Frontier). Tu especialidad es razonamiento lógico profundo, ciencias, matemáticas y programación paso a paso en español."
    );
}

object KofuEditionProfile {
    const val STRICT_2GB_PROCESS_RSS_MB = 1850L
    const val LOW_RAM_DEVICE_THRESHOLD_MB = 6144L

    @Volatile
    var deviceTotalRamMb: Long = 0L
        private set

    val current: SiliconEdition by lazy { fromCode(BuildConfig.SILICON_TIER) }

    val isStrict2GbBudgetActive: Boolean
        get() = current == SiliconEdition.NODE_7NM_LEGACY ||
            (deviceTotalRamMb in 1L..LOW_RAM_DEVICE_THRESHOLD_MB) ||
            current == SiliconEdition.NODE_5NM_MAINSTREAM

    val effectiveMaxRagChunks: Int
        get() = if (isStrict2GbBudgetActive) 1 else current.maxRagChunks

    val effectiveMaxRagContextChars: Int
        get() = if (isStrict2GbBudgetActive) 180 else current.maxRagContextChars

    val effectiveMaxTokens: Int
        get() = if (isStrict2GbBudgetActive) minOf(current.defaultMaxTokens, 256) else current.defaultMaxTokens

    fun configureForDeviceRam(totalRamBytes: Long) {
        if (totalRamBytes > 0L) {
            deviceTotalRamMb = totalRamBytes / (1024L * 1024L)
        }
    }

    fun fromCode(code: String): SiliconEdition {
        return SiliconEdition.entries.find { it.code == code } ?: SiliconEdition.NODE_5NM_MAINSTREAM
    }
}
