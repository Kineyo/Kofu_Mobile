package com.kofu.assistant.repository

import android.util.Log
import java.nio.ByteBuffer

fun interface NativeStreamCallback {
    fun onToken(token: String)
}

object KofuNativeEngine {
    private const val TAG = "KofuNativeEngine"

    private var isLibraryLoaded = false

    init {
        try {
            System.loadLibrary("kofu_engine")
            isLibraryLoaded = true
            Log.i(TAG, "Librería nativa libkofu_engine.so cargada correctamente (v2.0)")
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo cargar la librería nativa libkofu_engine.so", t)
            isLibraryLoaded = false
        }
    }

    fun isNativeReady(): Boolean = isLibraryLoaded

    external fun nativeInit(): Boolean
    external fun nativeLoadModel(modelPath: String): Boolean
    external fun nativeLoadModelFd(fd: Int, size: Long, name: String): Boolean
    external fun nativeUnloadModel()
    external fun nativeIsModelLoaded(): Boolean
    external fun nativeGetModelName(): String
    external fun nativeGetBackendType(): Int
    external fun nativeGenerateStream(prompt: String, callback: NativeStreamCallback)
    external fun nativeGenerateStreamHistory(
        roles: Array<String>,
        messages: Array<String>,
        callback: NativeStreamCallback
    )
    external fun nativeCancelGeneration()
    external fun nativeSetGenerationConfig(
        temperature: Float,
        topP: Float,
        repeatPenalty: Float,
        maxTokens: Int,
        systemPrompt: String
    )
    external fun nativeGetStatsJson(): String
    external fun nativeTrimMemory()
    external fun nativeTrimMemoryLevel(level: Int)
    external fun nativeGetActiveHardwareTier(): String
    external fun nativeSetPreferredAccelerator(mode: Int)
    external fun nativeSetLowRamBudget(enabled: Boolean, maxRssMb: Long, debugGovernor: Boolean)

    // Micro-embedder GGUF y scorer NEON int8 para RAG semántico local
    external fun nativeLoadEmbedModel(modelPath: String): Boolean
    external fun nativeUnloadEmbedModel()
    external fun nativeIsEmbedModelLoaded(): Boolean
    external fun nativeGetEmbedDim(): Int
    external fun nativeEmbedTextInt8(text: String): ByteArray?
    external fun nativeScoreInt8Cosine(
        queryVec: ByteArray,
        corpusFlat: ByteArray,
        numDocs: Int,
        dim: Int
    ): FloatArray?
    external fun nativeScoreInt8CosineDirect(
        queryVec: ByteArray,
        corpusDirect: ByteBuffer,
        numDocs: Int,
        dim: Int
    ): FloatArray?

    // Motor jerárquico UFS MoE (Hot/Warm/Cold estilo Colibri, 100% MIT)
    external fun nativeProbeUfsStorage(appFilesDir: String): Double
    external fun nativeConfigureMoePager(warmBudgetMb: Long, totalRamMb: Long, forceUfs: Boolean)
    external fun nativeUpdateThermalStatus(thermalStatus: Int): Int
    external fun nativeGetMoePagerStatsJson(): String
}
