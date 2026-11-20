#pragma once

#include "model_loader.hpp"
#include "moe_ufs_pager.hpp"
#include "prompt_formatter.hpp"
#include <string>
#include <functional>
#include <memory>
#include <atomic>
#include <mutex>
#include <vector>
#include <cstdint>

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_vocab;
struct ggml_threadpool;

namespace kofu {

enum class HardwareTier {
    NONE = 0,
    NPU = 1,
    GPU = 2,
    CPU = 3
};

enum class PreferredAccelerator {
    AUTO_NPU_GPU_CPU = 0,
    FORCE_NPU = 1,
    FORCE_GPU = 2,
    FORCE_CPU = 3
};

struct GenerationConfig {
    float temperature{0.7f};
    float top_p{0.9f};
    float repeat_penalty{1.1f};
    int32_t max_tokens{2048};
    std::string system_prompt{
        "Eres Aura, una asistente educativa local útil, atenta y privada. Responde de forma clara, precisa y concisa en español."
    };
};

struct BenchmarkStats {
    uint32_t tokens_generated{0};
    double elapsed_seconds{0.0};
    double tokens_per_second{0.0};
    double decode_tokens_per_second{0.0};
    double prefill_ms{0.0};
    double load_ms{0.0};
    size_t memory_rss_mb{0};
    size_t anon_rss_mb{0};
    size_t available_ram_mb{0};
    int32_t active_n_ctx{0};
    std::string backend_name{"llama.cpp ARMv8"};
    std::string hardware_tier{"NONE"};
    std::string soc_model{"unknown"};
    std::string ram_governor_state{"OFF"};
};

class KofuEngine {
public:
    static KofuEngine& instance();

    bool load_model(const std::string& filepath);
    bool load_model_fd(int fd, size_t size, const std::string& name);
    void unload_model();

    bool is_model_loaded() const;
    std::string get_model_name() const;
    int get_backend_type() const;
    std::string get_active_hardware_tier() const;
    void set_preferred_accelerator(int mode);
    void set_low_ram_budget(bool enabled, size_t max_rss_mb, bool debug_governor_enabled = false);
    void trim_memory(int level = 80);

    // Motor jerárquico UFS MoE (Hot/Warm/Cold estilo Colibri, 100% MIT)
    double probe_ufs_storage(const std::string& app_files_dir);
    void configure_moe_pager(size_t warm_budget_mb, size_t total_ram_mb, bool force_ufs_mode = false);
    uint32_t update_thermal_status(int android_thermal_status);
    std::string get_moe_pager_stats_json() const;

    // Micro-embedder GGUF secundario para RAG semántico local
    bool load_embed_model(const std::string& filepath);
    void unload_embed_model();
    bool is_embed_model_loaded() const;
    int32_t get_embed_dim() const;
    std::vector<int8_t> embed_text_int8(const std::string& text);
    static std::vector<float> score_int8_cosine(
        const std::vector<int8_t>& query_vec,
        const int8_t* corpus_flat,
        int32_t num_docs,
        int32_t dim
    );

    void set_generation_config(const GenerationConfig& config);

    void generate_stream(
        const std::string& prompt,
        const std::function<void(const std::string&)>& token_callback
    );

    void generate_stream_history(
        const std::vector<ChatTurn>& history,
        const std::function<void(const std::string&)>& token_callback
    );

    void cancel_generation();
    std::string get_stats_json() const;

    static size_t get_process_rss_mb();
    static size_t get_process_anon_rss_mb();
    static size_t get_available_ram_mb();

private:
    KofuEngine();
    ~KofuEngine();

    KofuEngine(const KofuEngine&) = delete;
    KofuEngine& operator=(const KofuEngine&) = delete;

    void detect_snapdragon_topology();
    bool initialize_with_hardware_fallback(const std::string& source_path, const std::string& display_name);
    void warmup_system_prompt_locked();
    void unload_model_locked();
    void unload_embed_model_locked();
    void free_threadpools_locked();
    llama_context* init_context_with_fallback(llama_model* model, HardwareTier tier);

    mutable std::mutex engine_mutex_;
    mutable std::mutex embed_mutex_;
    mutable std::mutex stats_mutex_;
    std::unique_ptr<ModelLoader> loader_;
    llama_model* lmodel_{nullptr};
    llama_context* lctx_{nullptr};
    llama_model* embed_model_{nullptr};
    llama_context* embed_ctx_{nullptr};
    int32_t embed_dim_{0};
    std::atomic<bool> is_embed_loaded_{false};
    ggml_threadpool* threadpool_{nullptr};
    ggml_threadpool* threadpool_batch_{nullptr};
    std::string loaded_path_;
    std::string model_name_;
    int owned_fd_{-1};

    // Topología SoC Snapdragon / Kryo
    std::string soc_model_{"Android Edge"};
    std::vector<int> perf_core_ids_;
    bool has_dotprod_{false};
    bool has_fp16_{false};
    bool has_i8mm_{false};

    HardwareTier active_tier_{HardwareTier::NONE};
    PreferredAccelerator preferred_accelerator_{PreferredAccelerator::AUTO_NPU_GPU_CPU};

    GenerationConfig gen_config_{};
    std::vector<int32_t> kv_cache_tokens_;

    std::atomic<bool> is_model_loaded_{false};
    std::atomic<bool> is_generating_{false};
    std::atomic<bool> is_cancelled_{false};
    std::atomic<bool> low_ram_mode_{true};
    std::atomic<bool> debug_ram_governor_enabled_{false};
    std::atomic<size_t> max_rss_budget_mb_{1850};
    std::atomic<int> thermal_status_{0};
    std::atomic<uint32_t> thermal_throttle_ms_{0};
    MoeUfsPager moe_pager_;

    BenchmarkStats last_stats_{};
};

} // namespace kofu
