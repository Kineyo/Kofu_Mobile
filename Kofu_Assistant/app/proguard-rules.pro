# Reglas de ProGuard / R8 para Kofu Assistant

# Preservar el puente JNI y callbacks invocados desde libkofu_engine.so
-keep class com.kofu.assistant.repository.KofuNativeEngine {
    *;
}
-keepclassmembers class com.kofu.assistant.repository.KofuNativeEngine {
    native <methods>;
}

-keep interface com.kofu.assistant.repository.NativeStreamCallback {
    void onToken(java.lang.String);
}
-keepclassmembers class * implements com.kofu.assistant.repository.NativeStreamCallback {
    public void onToken(java.lang.String);
}

# Preservar modelos de datos serializados, RAG y configuración
-keep class com.kofu.assistant.data.** { *; }
-keep class com.kofu.assistant.data.rag.** { *; }
-keep class com.kofu.assistant.repository.TurnMessage { *; }
-keep class com.kofu.assistant.repository.ModelOption { *; }
-keep class com.kofu.assistant.repository.DebugMetrics { *; }
-dontwarn org.json.**

# OkHttp y Kotlin Coroutines
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn javax.annotation.**
-dontwarn org.conscrypt.**
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase
