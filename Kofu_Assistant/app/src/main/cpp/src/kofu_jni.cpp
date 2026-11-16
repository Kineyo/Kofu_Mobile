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
    if (path_chars == nullptr) return JNI_FALSE;
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
        if (name_chars != nullptr) {
            name = std::string(name_chars);
            env->ReleaseStringUTFChars(j_name, name_chars);
        }
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
        env->DeleteLocalRef(callback_class);
        return;
    }

    auto token_emitter = [env, j_callback, on_token_method](const std::string& token) {
        if (token.empty() || env->ExceptionCheck()) return;
        jstring j_token = env->NewStringUTF(token.c_str());
        env->CallVoidMethod(j_callback, on_token_method, j_token);
        env->DeleteLocalRef(j_token);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    };

    kofu::KofuEngine::instance().generate_stream(prompt, token_emitter);
    env->DeleteLocalRef(callback_class);
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

    jsize count_roles = env->GetArrayLength(j_roles);
    jsize count_messages = env->GetArrayLength(j_messages);
    jsize count = std::min(count_roles, count_messages);
    std::vector<kofu::ChatTurn> history;
    history.reserve(count);

    for (jsize i = 0; i < count; i++) {
        auto j_role = static_cast<jstring>(env->GetObjectArrayElement(j_roles, i));
        auto j_msg = static_cast<jstring>(env->GetObjectArrayElement(j_messages, i));

        std::string role_str = "user";
        std::string msg_str = "";

        if (j_role != nullptr) {
            const char* r_chars = env->GetStringUTFChars(j_role, nullptr);
            if (r_chars != nullptr) {
                role_str = r_chars;
                env->ReleaseStringUTFChars(j_role, r_chars);
            }
            env->DeleteLocalRef(j_role);
        }

        if (j_msg != nullptr) {
            const char* m_chars = env->GetStringUTFChars(j_msg, nullptr);
            if (m_chars != nullptr) {
                msg_str = m_chars;
                env->ReleaseStringUTFChars(j_msg, m_chars);
            }
            env->DeleteLocalRef(j_msg);
        }

        history.push_back({role_str, msg_str});
    }

    jclass callback_class = env->GetObjectClass(j_callback);
    jmethodID on_token_method = env->GetMethodID(callback_class, "onToken", "(Ljava/lang/String;)V");
    if (!on_token_method) {
        env->DeleteLocalRef(callback_class);
        return;
    }

    // Dado que se ejecuta en el mismo hilo de Dispatchers.IO, reutilizamos env directamente
    // evitando el costoso Attach/Detach en cada token individual.
    auto token_emitter = [env, j_callback, on_token_method](const std::string& token) {
        if (token.empty() || env->ExceptionCheck()) return;
        jstring j_token = env->NewStringUTF(token.c_str());
        env->CallVoidMethod(j_callback, on_token_method, j_token);
        env->DeleteLocalRef(j_token);
        if (env->ExceptionCheck()) {
            env->ExceptionClear();
        }
    };

    kofu::KofuEngine::instance().generate_stream_history(history, token_emitter);
    env->DeleteLocalRef(callback_class);
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeCancelGeneration(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    kofu::KofuEngine::instance().cancel_generation();
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeSetGenerationConfig(
    JNIEnv* env,
    jobject /*thiz*/,
    jfloat j_temperature,
    jfloat j_top_p,
    jfloat j_repeat_penalty,
    jint j_max_tokens,
    jstring j_system_prompt
) {
    kofu::GenerationConfig cfg;
    cfg.temperature = static_cast<float>(j_temperature);
    cfg.top_p = static_cast<float>(j_top_p);
    cfg.repeat_penalty = static_cast<float>(j_repeat_penalty);
    cfg.max_tokens = static_cast<int32_t>(j_max_tokens);

    if (j_system_prompt != nullptr) {
        const char* sys_chars = env->GetStringUTFChars(j_system_prompt, nullptr);
        if (sys_chars != nullptr) {
            cfg.system_prompt = std::string(sys_chars);
            env->ReleaseStringUTFChars(j_system_prompt, sys_chars);
        }
    }

    kofu::KofuEngine::instance().set_generation_config(cfg);
}

JNIEXPORT jstring JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGetStatsJson(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::string stats = kofu::KofuEngine::instance().get_stats_json();
    return env->NewStringUTF(stats.c_str());
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeTrimMemory(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    kofu::KofuEngine::instance().trim_memory(80);
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeTrimMemoryLevel(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint level
) {
    kofu::KofuEngine::instance().trim_memory(static_cast<int>(level));
}

JNIEXPORT jboolean JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeLoadEmbedModel(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring j_model_path
) {
    if (j_model_path == nullptr) return JNI_FALSE;
    const char* path_chars = env->GetStringUTFChars(j_model_path, nullptr);
    if (path_chars == nullptr) return JNI_FALSE;
    std::string path(path_chars);
    env->ReleaseStringUTFChars(j_model_path, path_chars);

    bool ok = kofu::KofuEngine::instance().load_embed_model(path);
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeUnloadEmbedModel(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    kofu::KofuEngine::instance().unload_embed_model();
}

JNIEXPORT jboolean JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeIsEmbedModelLoaded(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    return kofu::KofuEngine::instance().is_embed_model_loaded() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGetEmbedDim(
    JNIEnv* /*env*/,
    jobject /*thiz*/
) {
    return static_cast<jint>(kofu::KofuEngine::instance().get_embed_dim());
}

JNIEXPORT jbyteArray JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeEmbedTextInt8(
    JNIEnv* env,
    jobject /*thiz*/,
    jstring j_text
) {
    if (j_text == nullptr) return nullptr;
    const char* chars = env->GetStringUTFChars(j_text, nullptr);
    if (chars == nullptr) return nullptr;
    std::string text(chars);
    env->ReleaseStringUTFChars(j_text, chars);

    std::vector<int8_t> vec = kofu::KofuEngine::instance().embed_text_int8(text);
    if (vec.empty()) return nullptr;

    jbyteArray arr = env->NewByteArray(static_cast<jsize>(vec.size()));
    if (!arr) return nullptr;
    env->SetByteArrayRegion(arr, 0, static_cast<jsize>(vec.size()), reinterpret_cast<const jbyte*>(vec.data()));
    return arr;
}

JNIEXPORT jfloatArray JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeScoreInt8Cosine(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray j_query_vec,
    jbyteArray j_corpus_flat,
    jint j_num_docs,
    jint j_dim
) {
    if (j_query_vec == nullptr || j_corpus_flat == nullptr || j_num_docs <= 0 || j_dim <= 0) {
        return nullptr;
    }

    jsize q_len = env->GetArrayLength(j_query_vec);
    jsize c_len = env->GetArrayLength(j_corpus_flat);
    if (q_len != j_dim || static_cast<int64_t>(c_len) < static_cast<int64_t>(j_num_docs) * static_cast<int64_t>(j_dim)) {
        return nullptr;
    }

    std::vector<int8_t> q_vec(static_cast<size_t>(q_len));
    env->GetByteArrayRegion(j_query_vec, 0, q_len, reinterpret_cast<jbyte*>(q_vec.data()));

    jbyte* corpus_ptr = env->GetByteArrayElements(j_corpus_flat, nullptr);
    if (corpus_ptr == nullptr) return nullptr;

    std::vector<float> scores = kofu::KofuEngine::score_int8_cosine(
        q_vec,
        reinterpret_cast<const int8_t*>(corpus_ptr),
        static_cast<int32_t>(j_num_docs),
        static_cast<int32_t>(j_dim)
    );
    env->ReleaseByteArrayElements(j_corpus_flat, corpus_ptr, JNI_ABORT);

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(scores.size()));
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(scores.size()), scores.data());
    return out;
}

JNIEXPORT jfloatArray JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeScoreInt8CosineDirect(
    JNIEnv* env,
    jobject /*thiz*/,
    jbyteArray j_query_vec,
    jobject j_corpus_direct,
    jint j_num_docs,
    jint j_dim
) {
    if (j_query_vec == nullptr || j_corpus_direct == nullptr || j_num_docs <= 0 || j_dim <= 0) {
        return nullptr;
    }

    jsize q_len = env->GetArrayLength(j_query_vec);
    void* direct_ptr = env->GetDirectBufferAddress(j_corpus_direct);
    jlong cap = env->GetDirectBufferCapacity(j_corpus_direct);
    if (q_len != j_dim || direct_ptr == nullptr || cap < static_cast<jlong>(j_num_docs) * static_cast<jlong>(j_dim)) {
        return nullptr;
    }

    std::vector<int8_t> q_vec(static_cast<size_t>(q_len));
    env->GetByteArrayRegion(j_query_vec, 0, q_len, reinterpret_cast<jbyte*>(q_vec.data()));

    std::vector<float> scores = kofu::KofuEngine::score_int8_cosine(
        q_vec,
        reinterpret_cast<const int8_t*>(direct_ptr),
        static_cast<int32_t>(j_num_docs),
        static_cast<int32_t>(j_dim)
    );

    jfloatArray out = env->NewFloatArray(static_cast<jsize>(scores.size()));
    if (!out) return nullptr;
    env->SetFloatArrayRegion(out, 0, static_cast<jsize>(scores.size()), scores.data());
    return out;
}

JNIEXPORT jstring JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeGetActiveHardwareTier(
    JNIEnv* env,
    jobject /*thiz*/
) {
    std::string tier = kofu::KofuEngine::instance().get_active_hardware_tier();
    return env->NewStringUTF(tier.c_str());
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeSetPreferredAccelerator(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jint mode
) {
    kofu::KofuEngine::instance().set_preferred_accelerator(static_cast<int>(mode));
}

JNIEXPORT void JNICALL
Java_com_kofu_assistant_repository_KofuNativeEngine_nativeSetLowRamBudget(
    JNIEnv* /*env*/,
    jobject /*thiz*/,
    jboolean j_enabled,
    jlong j_max_rss_mb,
    jboolean j_debug_governor
) {
    kofu::KofuEngine::instance().set_low_ram_budget(
        j_enabled == JNI_TRUE,
        static_cast<size_t>(std::max<jlong>(512, j_max_rss_mb)),
        j_debug_governor == JNI_TRUE
    );
}

} // extern "C"
