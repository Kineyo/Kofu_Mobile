#pragma once

#include <string>
#include <memory>
#include <functional>
#include <atomic>
#include <vector>
#include <mutex>
#include "model_loader.hpp"
#include "prompt_formatter.hpp"

namespace kofu {

struct InferenceStats {
    uint32_t tokens_generated = 0;
    double elapsed_seconds = 0.0;
    double tokens_per_second = 0.0;
    size_t memory_rss_mb = 0;
    std::string backend_name;
};

class KofuEngine {
public:
    static KofuEngine& instance();

    bool load_model(const std::string& filepath);
    bool load_model_fd(int fd, size_t size, const std::string& name = "imported_model.gguf");
    void unload_model();

    bool is_model_loaded() const;
    std::string get_model_name() const;
    int get_backend_type() const; // 0=NONE, 1=LITERT, 2=GGUF

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

private:
    KofuEngine();
    ~KofuEngine();

    mutable std::mutex engine_mutex_;
    std::unique_ptr<ModelLoader> loader_;
    std::atomic<bool> is_cancelled_{false};
    std::atomic<bool> is_generating_{false};
    InferenceStats last_stats_{};

    static size_t get_process_rss_mb();
};

} // namespace kofu
