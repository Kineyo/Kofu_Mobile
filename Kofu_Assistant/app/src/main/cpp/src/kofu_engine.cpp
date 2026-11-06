#include "kofu_engine.hpp"
#include "prompt_formatter.hpp"

#include "llama.h"

#include <android/log.h>
#include <unistd.h>
#include <chrono>
#include <thread>
#include <sstream>
#include <fstream>
#include <vector>
#include <algorithm>
#include <cmath>
#include <ctime>

#define TAG "KofuEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static size_t count_incomplete_utf8_trailing_bytes(const std::string& s) {
    if (s.empty()) return 0;
    size_t len = s.length();
    for (size_t i = 1; i <= 4 && i <= len; ++i) {
        unsigned char c = static_cast<unsigned char>(s[len - i]);
        if ((c & 0x80) == 0x00) {
            return 0; // Byte ASCII completo
        } else if ((c & 0xC0) == 0x80) {
            continue; // Byte de continuación UTF-8
        } else if ((c & 0xE0) == 0xC0) {
            return (i < 2) ? i : 0; // Inicio 2 bytes
        } else if ((c & 0xF0) == 0xE0) {
            return (i < 3) ? i : 0; // Inicio 3 bytes
        } else if ((c & 0xF8) == 0xF0) {
            return (i < 4) ? i : 0; // Inicio 4 bytes
        } else {
            return 0;
        }
    }
    return 0;
}

namespace kofu {

KofuEngine& KofuEngine::instance() {
    static KofuEngine engine;
    return engine;
}

KofuEngine::KofuEngine() : loader_(std::make_unique<ModelLoader>()) {
    ggml_backend_load_all();
    LOGI("KofuEngine con llama.cpp inicializado para Android ARM64");
}

KofuEngine::~KofuEngine() {
    unload_model();
}

void KofuEngine::unload_model_locked() {
    cancel_generation();
    is_model_loaded_.store(false);

    if (lctx_) {
        llama_free(lctx_);
        lctx_ = nullptr;
    }
    if (lmodel_) {
        llama_model_free(lmodel_);
        lmodel_ = nullptr;
    }
    if (owned_fd_ >= 0) {
        close(owned_fd_);
        owned_fd_ = -1;
    }
    if (loader_) {
        loader_->unload();
    }
    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        loaded_path_.clear();
        model_name_.clear();
    }
}

void KofuEngine::unload_model() {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    unload_model_locked();
    LOGI("Modelo y contexto llama.cpp liberados correctamente");
}

bool KofuEngine::load_model(const std::string& filepath) {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    unload_model_locked();

    // 1. Validar integridad binaria de cabecera (Magic GGUF / LiteRT)
    if (!loader_->load(filepath)) {
        LOGE("Fallo en validación binaria para: %s", filepath.c_str());
        return false;
    }

    // 2. Parámetros del modelo llama.cpp
    llama_model_params mparams = llama_model_default_params();

    lmodel_ = llama_model_load_from_file(filepath.c_str(), mparams);
    if (!lmodel_) {
        LOGE("llama_model_load_from_file falló para %s", filepath.c_str());
        loader_->unload();
        return false;
    }

    // 3. Crear contexto de inferencia para CPU ARM64
    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;

    // Detectar número óptimo de hilos de CPU (en teléfonos, los núcleos de rendimiento suelen ser 4)
    unsigned int hardware_threads = std::thread::hardware_concurrency();
    int32_t optimal_threads = 4;
    if (hardware_threads > 0) {
        optimal_threads = static_cast<int32_t>(std::min(4u, std::max(2u, hardware_threads / 2)));
        if (hardware_threads >= 6) optimal_threads = 4;
    }
    cparams.n_threads = optimal_threads;
    cparams.n_threads_batch = optimal_threads;

    lctx_ = llama_init_from_model(lmodel_, cparams);
    if (!lctx_) {
        LOGE("llama_init_from_model falló para %s", filepath.c_str());
        llama_model_free(lmodel_);
        lmodel_ = nullptr;
        loader_->unload();
        return false;
    }

    is_model_loaded_.store(true);
    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        loaded_path_ = filepath;
        model_name_ = loader_->get_metadata().filename;
    }
    LOGI("Modelo GGUF cargado exitosamente en motor llama.cpp nativo: %s", model_name_.c_str());
    return true;
}

bool KofuEngine::load_model_fd(int fd, size_t size, const std::string& name) {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    unload_model_locked();

    if (fd < 0) {
        LOGE("Descriptor de archivo inválido: fd=%d", fd);
        return false;
    }

    owned_fd_ = fd;

    if (!loader_->load_fd(fd, size, name)) {
        LOGE("Fallo en validación binaria de descriptor fd=%d", fd);
        unload_model_locked();
        return false;
    }

    // En Android Linux, podemos acceder directamente al descriptor abierto vía /proc/self/fd/<fd>
    std::string proc_fd_path = "/proc/self/fd/" + std::to_string(fd);

    llama_model_params mparams = llama_model_default_params();

    lmodel_ = llama_model_load_from_file(proc_fd_path.c_str(), mparams);
    if (!lmodel_) {
        LOGE("llama_model_load_from_file falló para FD %s", proc_fd_path.c_str());
        unload_model_locked();
        return false;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 2048;
    cparams.n_batch = 512;

    unsigned int hardware_threads = std::thread::hardware_concurrency();
    int32_t optimal_threads = 4;
    if (hardware_threads > 0) {
        optimal_threads = static_cast<int32_t>(std::min(4u, std::max(2u, hardware_threads / 2)));
        if (hardware_threads >= 6) optimal_threads = 4;
    }
    cparams.n_threads = optimal_threads;
    cparams.n_threads_batch = optimal_threads;

    lctx_ = llama_init_from_model(lmodel_, cparams);
    if (!lctx_) {
        LOGE("llama_init_from_model falló para descriptor fd=%d", fd);
        unload_model_locked();
        return false;
    }

    is_model_loaded_.store(true);
    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        loaded_path_ = proc_fd_path;
        model_name_ = name;
    }
    LOGI("Modelo cargado vía FD en llama.cpp nativo: %s", model_name_.c_str());
    return true;
}

bool KofuEngine::is_model_loaded() const {
    return is_model_loaded_.load();
}

std::string KofuEngine::get_model_name() const {
    std::lock_guard<std::mutex> lock(stats_mutex_);
    return model_name_;
}

int KofuEngine::get_backend_type() const {
    if (!is_model_loaded_.load()) return 0;
    return 2; // GGUF llama.cpp ARM64
}

size_t KofuEngine::get_process_rss_mb() {
    std::ifstream statm("/proc/self/statm");
    if (!statm.is_open()) return 0;
    size_t size = 0, resident = 0;
    statm >> size >> resident;
    long page_size_kb = sysconf(_SC_PAGESIZE) / 1024;
    return (resident * page_size_kb) / 1024;
}

void KofuEngine::generate_stream(
    const std::string& prompt,
    const std::function<void(const std::string&)>& token_callback
) {
    std::vector<ChatTurn> history = {
        {"user", prompt}
    };
    generate_stream_history(history, token_callback);
}

void KofuEngine::generate_stream_history(
    const std::vector<ChatTurn>& history,
    const std::function<void(const std::string&)>& token_callback
) {
    std::lock_guard<std::mutex> lock(engine_mutex_);

    if (!lmodel_ || !lctx_) {
        token_callback("Error [llama.cpp Backend]: Ningún modelo GGUF cargado en memoria.");
        return;
    }

    is_cancelled_.store(false);
    is_generating_.store(true);

    auto start_time = std::chrono::steady_clock::now();

    // 1. Determinar plantilla según el nombre del modelo
    PromptStyle prompt_style = PromptStyle::GEMMA;
    std::string current_name;
    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        current_name = model_name_;
    }
    std::string lower_name = current_name;
    std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), ::tolower);
    if (lower_name.find("gemma4") != std::string::npos || lower_name.find("gemma-4") != std::string::npos) {
        prompt_style = PromptStyle::GEMMA_4;
    } else if (lower_name.find("chatml") != std::string::npos || lower_name.find("qwen") != std::string::npos) {
        prompt_style = PromptStyle::CHATML;
    }

    std::string formatted_prompt = PromptFormatter::format_multi_turn_prompt(
        history,
        "Eres Kofu, un asistente de IA local útil, atento, privado y respetuoso. Responde de forma clara, precisa y concisa.",
        prompt_style
    );

    const llama_vocab* vocab = llama_model_get_vocab(lmodel_);
    if (!vocab) {
        token_callback("Error: No se pudo obtener el vocabulario del modelo.");
        is_generating_.store(false);
        return;
    }

    // 2. Tokenizar prompt formateado
    std::vector<llama_token> tokens_list(formatted_prompt.size() + 64);
    int32_t n_tokens = llama_tokenize(
        vocab,
        formatted_prompt.c_str(),
        static_cast<int32_t>(formatted_prompt.length()),
        tokens_list.data(),
        static_cast<int32_t>(tokens_list.size()),
        true,
        true
    );

    if (n_tokens < 0) {
        tokens_list.resize(-n_tokens);
        n_tokens = llama_tokenize(
            vocab,
            formatted_prompt.c_str(),
            static_cast<int32_t>(formatted_prompt.length()),
            tokens_list.data(),
            static_cast<int32_t>(tokens_list.size()),
            true,
            true
        );
    }

    if (n_tokens <= 0) {
        token_callback("Error al tokenizar la entrada.");
        is_generating_.store(false);
        return;
    }
    tokens_list.resize(n_tokens);

    // Salvaguarda de presupuesto de contexto (máximo 1536 tokens de entrada para dejar 512 de respuesta)
    const int32_t max_context = 2048;
    const int32_t max_prompt_budget = max_context - 384;
    if (static_cast<int32_t>(tokens_list.size()) > max_prompt_budget) {
        LOGW("Prompt excede presupuesto de contexto (%zu tokens), podando historial...", tokens_list.size());
        size_t excess = tokens_list.size() - max_prompt_budget;
        tokens_list.erase(tokens_list.begin(), tokens_list.begin() + excess);
    }

    // 3. Limpiar memoria previa del contexto KV
    llama_memory_clear(llama_get_memory(lctx_), true);

    // 4. Inicializar cadena de muestreo (Sampler Chain)
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);
    llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(smpl, llama_sampler_init_top_p(0.9f, 1));
    llama_sampler_chain_add(smpl, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(std::time(nullptr))));

    // 5. Procesar los tokens de entrada por lotes
    llama_batch batch = llama_batch_init(512, 0, 1);
    bool prompt_interrupted = false;
    for (size_t i = 0; i < tokens_list.size(); i += 512) {
        if (is_cancelled_.load()) {
            prompt_interrupted = true;
            break;
        }

        int32_t n_eval = static_cast<int32_t>(std::min(static_cast<size_t>(512), tokens_list.size() - i));
        for (int32_t j = 0; j < n_eval; ++j) {
            batch.token[j] = tokens_list[i + j];
            batch.pos[j] = static_cast<llama_pos>(i + j);
            batch.n_seq_id[j] = 1;
            batch.seq_id[j][0] = 0;
            batch.logits[j] = (i + j == tokens_list.size() - 1);
        }
        batch.n_tokens = n_eval;

        if (llama_decode(lctx_, batch) != 0) {
            LOGE("llama_decode falló al procesar tokens del prompt");
            token_callback("\n[Error de decodificación en prompt]");
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            is_generating_.store(false);
            return;
        }
    }

    if (prompt_interrupted || is_cancelled_.load()) {
        token_callback("\n[Generación cancelada]");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        is_generating_.store(false);
        return;
    }

    // 6. Bucle de generación autoregresiva con emisor seguro UTF-8
    llama_pos n_cur = static_cast<llama_pos>(tokens_list.size());
    const int32_t max_generated_tokens = 2048;
    uint32_t token_count = 0;

    std::string utf8_accumulator;
    auto emit_utf8_safe = [&](const std::string& piece, bool flush = false) {
        utf8_accumulator += piece;
        if (flush) {
            if (!utf8_accumulator.empty()) {
                token_callback(utf8_accumulator);
                utf8_accumulator.clear();
            }
            return;
        }
        size_t incomplete = count_incomplete_utf8_trailing_bytes(utf8_accumulator);
        if (incomplete < utf8_accumulator.length()) {
            size_t valid_len = utf8_accumulator.length() - incomplete;
            token_callback(utf8_accumulator.substr(0, valid_len));
            utf8_accumulator.erase(0, valid_len);
        }
    };

    char piece_buf[256];

    while (token_count < max_generated_tokens && n_cur < max_context) {
        if (is_cancelled_.load()) {
            token_callback("\n[Generación cancelada]");
            break;
        }

        // Muestrear siguiente token a partir de los logits del último token
        llama_token new_token_id = llama_sampler_sample(smpl, lctx_, -1);
        llama_sampler_accept(smpl, new_token_id);

        // Comprobar token de fin de generación (EOG/EOS)
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

        // Convertir token a texto (piece) con manejo seguro contra desbordamiento de búfer
        int32_t n_piece = llama_token_to_piece(vocab, new_token_id, piece_buf, sizeof(piece_buf), 0, false);
        if (n_piece < 0) {
            std::vector<char> dynamic_buf(-n_piece);
            n_piece = llama_token_to_piece(vocab, new_token_id, dynamic_buf.data(), static_cast<int32_t>(dynamic_buf.size()), 0, false);
            if (n_piece > 0) {
                std::string piece_str(dynamic_buf.data(), n_piece);
                emit_utf8_safe(piece_str, false);
                token_count++;
            }
        } else if (n_piece > 0) {
            std::string piece_str(piece_buf, n_piece);
            emit_utf8_safe(piece_str, false);
            token_count++;
        }

        // Preparar batch para decodificar el nuevo token
        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        n_cur++;

        if (llama_decode(lctx_, batch) != 0) {
            LOGE("llama_decode falló durante la generación");
            break;
        }
    }

    // Vaciar bytes restantes acumulados
    emit_utf8_safe("", true);

    // 7. Liberar recursos de generación
    llama_batch_free(batch);
    llama_sampler_free(smpl);

    auto end_time = std::chrono::steady_clock::now();
    double elapsed = std::chrono::duration<double>(end_time - start_time).count();

    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        last_stats_.tokens_generated = token_count;
        last_stats_.elapsed_seconds = elapsed;
        last_stats_.tokens_per_second = (elapsed > 0.0) ? (token_count / elapsed) : 0.0;
        last_stats_.memory_rss_mb = get_process_rss_mb();
        last_stats_.backend_name = "llama.cpp ARM64";
    }

    is_generating_.store(false);
}

void KofuEngine::cancel_generation() {
    is_cancelled_.store(true);
}

std::string KofuEngine::get_stats_json() const {
    std::lock_guard<std::mutex> lock(stats_mutex_);
    std::ostringstream ss;
    ss << "{"
       << "\"tokens_generated\":" << last_stats_.tokens_generated << ","
       << "\"elapsed_seconds\":" << last_stats_.elapsed_seconds << ","
       << "\"tokens_per_sec\":" << last_stats_.tokens_per_second << ","
       << "\"memory_rss_mb\":" << last_stats_.memory_rss_mb << ","
       << "\"backend\":\"" << last_stats_.backend_name << "\""
       << "}";
    return ss.str();
}

} // namespace kofu
