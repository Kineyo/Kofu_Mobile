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
        editionTitle = "Aura-Kaze Beta (7nm Edge)",
        badgeLabel = "7nm • MIT UFS/eMMC",
        androidRange = "Android 5.0–7.x",
        aiTaskFocus = "Aura-Kaze-Go 1.1B Podado (100% MIT, <=3 GB / eMMC) y RAG Escolar Offline",
        isaTarget = "ARMv8.0-A NEON Universal (Sin DotProd/i8mm)",
        defaultModelId = "kaze-aura-go-1.1b",
        defaultMaxTokens = 256,
        defaultTemperature = 0.50f,
        maxRagChunks = 1,
        maxRagContextChars = 180,
        maxInMemoryMessages = 100,
        defaultSystemPrompt = "Tu nombre es Aura. Eres una asistente educativa y conversacional 100% offline en español basada en Kaze (licencia MIT). El usuario es una persona distinta a ti: nunca llames Aura al usuario ni digas que no tienes nombre propio."
    ),
    NODE_5NM_MAINSTREAM(
        code = "5NM_MAINSTREAM",
        editionTitle = "Aura-Kaze Beta (5nm UFS MoE)",
        badgeLabel = "5nm • UFS Hot/Warm/Cold",
        androidRange = "Android 8.0–15.0",
        aiTaskFocus = "Kaze-4.7B-A530M (Streaming UFS Hot/Warm/Cold, 100% MIT) + Programación, Matemáticas y Español",
        isaTarget = "ARMv8.2-A NEON + DotProd + FP16 (Kryo Prime/Perf)",
        defaultModelId = "kaze-4.7b-a530m",
        defaultMaxTokens = 640,
        defaultTemperature = 0.35f,
        maxRagChunks = 2,
        maxRagContextChars = 360,
        maxInMemoryMessages = 150,
        defaultSystemPrompt = "Tu nombre es Aura. Eres una asistente educativa 100% offline basada en Kaze-4.7B-A530M (licencia MIT), experta en programación, matemáticas y español. El usuario es una persona distinta a ti: nunca llames Aura al usuario ni digas que no tienes nombre propio. Responde paso a paso con claridad."
    ),
    FRONTIER_SUB5NM(
        code = "FRONTIER_SUB5NM",
        editionTitle = "Kaze Frontier Beta (<5nm)",
        badgeLabel = "<5nm • Kaze 4.7B Full",
        androidRange = "Android 14+ Frontera",
        aiTaskFocus = "Kaze-4.7B-A530M Base (31 Expertos MoE, 100% MIT) + Razonamiento y Ciencia Avanzada",
        isaTarget = "ARMv8.6-A / ARMv9 DotProd + FP16 + i8mm Matrix",
        defaultModelId = "kaze-4.7b-a530m",
        defaultMaxTokens = 2048,
        defaultTemperature = 0.45f,
        maxRagChunks = 3,
        maxRagContextChars = 720,
        maxInMemoryMessages = 400,
        defaultSystemPrompt = "Tu nombre es Aura (Edición Kaze Frontier 100% MIT). El usuario es una persona distinta a ti: nunca lo llames Aura. Tu especialidad es razonamiento lógico profundo, ciencias, matemáticas y programación paso a paso en español."
    );
}

object KofuEditionProfile {
    const val STRICT_2GB_PROCESS_RSS_MB = 1850L
    const val ULTRA_TIGHT_3GB_THRESHOLD_MB = 3400L
    const val LOW_RAM_DEVICE_THRESHOLD_MB = 6144L
    const val UFS_FAST_BANDWIDTH_THRESHOLD_MBPS = 600.0

    @Volatile
    var deviceTotalRamMb: Long = 0L
        private set

    @Volatile
    var storageBandwidthMbps: Double = 0.0
        private set

    val isUfsFastStorage: Boolean
        get() = storageBandwidthMbps >= UFS_FAST_BANDWIDTH_THRESHOLD_MBPS

    /**
     * Techo dinámico de VmRSS calibrado para serie Galaxy A (2 GB / 3 GB / 4 GB / 6 GB+ RAM)
     * para frenar antes de que actúe el Android Low Memory Killer (lmkd).
     */
    val effectiveMaxRssBudgetMb: Long
        get() = when {
            deviceTotalRamMb in 1L..2400L -> 750L
            deviceTotalRamMb in 2401L..3400L -> 1150L
            deviceTotalRamMb in 3401L..4500L -> 1500L
            else -> STRICT_2GB_PROCESS_RSS_MB
        }

    val current: SiliconEdition by lazy { fromCode(BuildConfig.SILICON_TIER) }

    val isUltraTight3GbDevice: Boolean
        get() = deviceTotalRamMb in 1L..ULTRA_TIGHT_3GB_THRESHOLD_MB

    val isStrict2GbBudgetActive: Boolean
        get() = current == SiliconEdition.NODE_7NM_LEGACY ||
            (deviceTotalRamMb in 1L..LOW_RAM_DEVICE_THRESHOLD_MB) ||
            current == SiliconEdition.NODE_5NM_MAINSTREAM

    val effectiveMaxRagChunks: Int
        get() = when {
            isUltraTight3GbDevice -> 1
            isStrict2GbBudgetActive -> minOf(current.maxRagChunks, 2)
            else -> current.maxRagChunks
        }

    val effectiveMaxRagContextChars: Int
        get() = when {
            isUltraTight3GbDevice -> 180
            isStrict2GbBudgetActive -> minOf(current.maxRagContextChars, 320)
            else -> current.maxRagContextChars
        }

    val effectiveMaxTokens: Int
        get() = when {
            isUltraTight3GbDevice -> minOf(current.defaultMaxTokens, 256)
            isStrict2GbBudgetActive -> minOf(current.defaultMaxTokens, 384)
            else -> current.defaultMaxTokens
        }

    /**
     * Enrutamiento híbrido adaptable (rama dev-test, 100% MIT, 0% Gemma):
     * - <= 3 GB RAM física o almacenamiento eMMC lento (< 600 MB/s): Aura-Kaze-Go 1.1B podado (~660 MB, residente en RAM).
     * - 4 GB+ RAM con almacenamiento UFS rápido (>= 600 MB/s) o Frontier: Kaze-4.7B-A530M Base con paginación Hot/Warm/Cold en UFS.
     */
    val effectiveDefaultModelId: String
        get() = when {
            isUltraTight3GbDevice -> "kaze-aura-go-1.1b"
            storageBandwidthMbps > 0.0 && !isUfsFastStorage -> "kaze-aura-go-1.1b"
            storageBandwidthMbps == 0.0 && deviceTotalRamMb in 1L..4500L -> "kaze-aura-go-1.1b"
            current == SiliconEdition.NODE_7NM_LEGACY && !isUfsFastStorage -> "kaze-aura-go-1.1b"
            else -> "kaze-4.7b-a530m"
        }

    fun configureForDeviceRam(totalRamBytes: Long) {
        if (totalRamBytes > 0L) {
            deviceTotalRamMb = totalRamBytes / (1024L * 1024L)
        }
    }

    fun configureForStorageBandwidth(mbps: Double) {
        if (mbps > 0.0) {
            storageBandwidthMbps = mbps
        }
    }

    fun fromCode(code: String): SiliconEdition {
        return SiliconEdition.entries.find { it.code == code } ?: SiliconEdition.NODE_5NM_MAINSTREAM
    }
}
