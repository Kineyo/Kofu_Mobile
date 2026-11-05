package com.kofu.assistant.repository

import android.util.Log

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
            Log.i(TAG, "Librería nativa libkofu_engine.so cargada correctamente")
        } catch (t: Throwable) {
            Log.e(TAG, "No se pudo cargar la librería nativa libkofu_engine.so", t)
            isLibraryLoaded = false
        }
    }

    fun isNativeReady(): Boolean = isLibraryLoaded

    external fun nativeInit(): Boolean
    external fun nativeLoadModel(modelPath: String): Boolean
    external fun nativeUnloadModel()
    external fun nativeIsModelLoaded(): Boolean
    external fun nativeGetModelName(): String
    external fun nativeGetBackendType(): Int
    external fun nativeGenerateStream(prompt: String, callback: NativeStreamCallback)
    external fun nativeCancelGeneration()
    external fun nativeGetStatsJson(): String
}
