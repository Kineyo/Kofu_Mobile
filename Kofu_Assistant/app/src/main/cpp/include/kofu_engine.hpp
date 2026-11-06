#pragma once

#include "model_loader.hpp"
#include "prompt_formatter.hpp"
#include <string>
#include <functional>
#include <memory>
#include <atomic>
#include <mutex>
#include <vector>

struct llama_model;
struct llama_context;
struct llama_sampler;
struct llama_vocab;

namespace kofu {

struct BenchmarkStats {
    uint32_t tokens_generated{0};
    double elapsed_seconds{0.0};
    double tokens_per_second{0.0};
    size_t memory_rss_mb{0};
    std::string backend_name{"llama.cpp ARM64"};
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

private:
    KofuEngine();
    ~KofuEngine();

    KofuEngine(const KofuEngine&) = delete;
    KofuEngine& operator=(const KofuEngine&) = delete;

    void unload_model_locked();

    mutable std::mutex engine_mutex_;
    std::unique_ptr<ModelLoader> loader_;
    llama_model* lmodel_{nullptr};
    llama_context* lctx_{nullptr};
    std::string loaded_path_;
    std::string model_name_;
    int owned_fd_{-1};

    std::atomic<bool> is_generating_{false};
    std::atomic<bool> is_cancelled_{false};

    BenchmarkStats last_stats_{};
};

} // namespace kofu
