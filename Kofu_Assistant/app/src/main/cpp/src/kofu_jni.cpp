#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "kofu_engine.hpp"
#include "prompt_formatter.hpp"

#define TAG "KofuJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static JavaVM* g_jvm = nullptr;

jint JNI_OnLoad(JavaVM* vm, void* /*reserved*/) {
    g_jvm = vm;
    LOGI("JNI_OnLoad: JavaVM registrado correctamente para Kofu Engine llama.cpp");
    return JNI_VERSION_1_6;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeInit(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    LOGI("Kofu Native llama.cpp Engine inicializado con soporte multi-turno");
    return JNI_TRUE;
}

JNIEXPORT jboolean JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeLoadModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring j_model_path
) {
    if (j_model_path == nullptr) return JNI_FALSE;

    const char* path_chars = env->GetStringUTFChars(j_model_path, nullptr);
    std::string path(path_chars);
    env->ReleaseStringUTFChars(j_model_path, path_chars);

    bool success = kofu::KofuEngine::instance().load_model(path);
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeLoadModelFd(
    JNIEnv* env,
    jobject /*thiz*/,
    jint j_fd,
    jlong j_size,
    jstring j_name
) {
    std::string name = "imported_model.gguf";
    if (j_name != nullptr) {
        const char* name_chars = env->GetStringUTFChars(j_name, nullptr);
        name = std::string(name_chars);
        env->ReleaseStringUTFChars(j_name, name_chars);
    }

    bool success = kofu::KofuEngine::instance().load_model_fd(
        static_cast<int>(j_fd),
        static_cast<size_t>(j_size),
        name
    );
    return success ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeUnloadModel(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    kofu::KofuEngine::instance().unload_model();
}

JNIEXPORT jboolean JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeIsModelLoaded(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    return kofu::KofuEngine::instance().is_model_loaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGetModelName(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::string name = kofu::KofuEngine::instance().get_model_name();
    return env->NewStringUTF(name.c_str());
}

JNIEXPORT jint JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGetBackendType(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    return static_cast<jint>(kofu::KofuEngine::instance().get_backend_type());
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGenerateStream(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring j_prompt,
    jobject j_callback
) {
    if (j_prompt == nullptr || j_callback == nullptr) return;

    const char* prompt_chars = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(prompt_chars);
    env->ReleaseStringUTFChars(j_prompt, prompt_chars);

    jclass callback_class = env->GetObjectClass(j_callback);
    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");

    if (on_token_method == nullptr) {
        LOGE("No se encontró el método onToken(String) en el callback");
        return;
    }

    auto token_emitter = [env, j_callback, on_token_method](const std::string& token) {
        if (env->ExceptionCheck()) return;
        jstring j_token = env->NewStringUTF(token.c_str());
        env->CallVoidMethod(j_callback, on_token_method, j_token);
        env->DeleteLocalRef(j_token);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    };

    kofu::KofuEngine::instance().generate_stream(prompt, token_emitter);
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGenerateStreamHistory(
    JNIEnv* env,
    jobject /*thiz*/,
    jobjectArray j_roles,
    jobjectArray j_messages,
    jobject j_callback
) {
    if (j_roles == nullptr || j_messages == nullptr || j_callback == nullptr) return;

    jsize count = env->GetArrayLength(j_roles);
    std::vector<kofu::ChatTurn> history;
    history.reserve(count);

    for (jsize i = 0; i < count; i++) {
        auto j_role = static_cast<jstring>(env->GetObjectArrayElement(j_roles, i));
        auto j_msg = static_cast<jstring>(env->GetObjectArrayElement(j_messages, i));

        const char* r_chars = env->GetStringUTFChars(j_role, nullptr);
        const char* m_chars = env->GetStringUTFChars(j_msg, nullptr);

        history.push_back({std::string(r_chars), std::string(m_chars)});

        env->ReleaseStringUTFChars(j_role, r_chars);
        env->ReleaseStringUTFChars(j_msg, m_chars);
        env->DeleteLocalRef(j_role);
        env->DeleteLocalRef(j_msg);
    }

    jclass callback_class = env->GetObjectClass(j_callback);
    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    if (!on_token_method) return;

    // Dado que se ejecuta en el mismo hilo de Dispatchers.IO, reutilizamos env directamente
    // evitando el costoso Attach/Detach en cada token individual.
    auto token_emitter = [env, j_callback, on_token_method](const std::string& token) {
        if (env->ExceptionCheck()) return;
        jstring j_token = env->NewStringUTF(token.c_str());
        env->CallVoidMethod(j_callback, on_token_method, j_token);
        env->DeleteLocalRef(j_token);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    };

    kofu::KofuEngine::instance().generate_stream_history(history, token_emitter);
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeCancelGeneration(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    kofu::KofuEngine::instance().cancel_generation();
}

JNIEXPORT jstring JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGetStatsJson(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::string stats = kofu::KofuEngine::instance().get_stats_json();
    return env->NewStringUTF(stats.c_str());
}

} // extern "C"
