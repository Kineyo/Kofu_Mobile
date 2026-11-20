#include "kofu_engine.hpp"
#include "prompt_formatter.hpp"

#include "llama.h"
#include "ggml-backend.h"
#include "ggml-cpu.h"

#include <android/log.h>
#include <sys/system_properties.h>
#include <sys/auxv.h>
#include <unistd.h>
#include <chrono>
#include <thread>
#include <sstream>
#include <fstream>
#include <vector>
#include <algorithm>
#include <cmath>
#include <cstring>
#include <ctime>

#if defined(__aarch64__)
#include <asm/hwcap.h>
#include <arm_neon.h>
#endif

#define TAG "KofuEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

#ifndef KOFU_SILICON_TIER_STR
#define KOFU_SILICON_TIER_STR "5NM"
#endif

#ifndef KOFU_ARM_ARCH_STR
#define KOFU_ARM_ARCH_STR "armv8.2-a+dotprod+fp16"
#endif

namespace {

size_t count_incomplete_utf8_trailing_bytes(const std::string& s) {
    if (s.empty()) return 0;
    size_t len = s.length();
    for (size_t i = 1; i <= 4 && i <= len; ++i) {
        unsigned char c = static_cast<unsigned char>(s[len - i]);
        if ((c & 0x80) == 0x00) {
            return 0;
        } else if ((c & 0xC0) == 0x80) {
            continue;
        } else if ((c & 0xE0) == 0xC0) {
            return (i < 2) ? i : 0;
        } else if ((c & 0xF0) == 0xE0) {
            return (i < 3) ? i : 0;
        } else if ((c & 0xF8) == 0xF0) {
            return (i < 4) ? i : 0;
        } else {
            return 0;
        }
    }
    return 0;
}

std::string read_sys_prop(const char* key) {
    char val[PROP_VALUE_MAX] = {0};
    int len = __system_property_get(key, val);
    if (len > 0) {
        return std::string(val, static_cast<size_t>(len));
    }
    return "";
}

bool contains_icase(const char* haystack, const char* needle) {
    if (!haystack || !needle) return false;
    std::string h(haystack);
    std::string n(needle);
    std::transform(h.begin(), h.end(), h.begin(), ::tolower);
    std::transform(n.begin(), n.end(), n.begin(), ::tolower);
    return h.find(n) != std::string::npos;
}

const char* tier_to_cstr(kofu::HardwareTier tier) {
    switch (tier) {
        case kofu::HardwareTier::NPU: return "NPU";
        case kofu::HardwareTier::GPU: return "GPU";
        case kofu::HardwareTier::CPU: return "CPU";
        default: return "NONE";
    }
}

std::string build_system_prefix(const std::string& sys_prompt, kofu::PromptStyle style) {
    const std::string clean = kofu::PromptFormatter::sanitize_input(sys_prompt);
    if (clean.empty()) return "";
    if (style == kofu::PromptStyle::GEMMA_4) {
        return "<|turn>user\n" + clean + "\n\n";
    } else if (style == kofu::PromptStyle::CHATML) {
        return "<|im_start|>system\n" + clean + "<|im_end|>\n";
    } else if (style == kofu::PromptStyle::LLAMA3) {
        return "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n\n" + clean + "<|eot_id|>";
    }
    return "<start_of_turn>user\n" + clean + "\n\n";
}

} // namespace

namespace kofu {

KofuEngine& KofuEngine::instance() {
    static KofuEngine engine;
    return engine;
}

KofuEngine::KofuEngine() : loader_(std::make_unique<ModelLoader>()) {
    ggml_backend_load_all();
    detect_snapdragon_topology();
    LOGI("KofuEngine Edge AI inicializado | SoC: %s | PerfCores: %zu | RAM libre: %zu MB",
         soc_model_.c_str(), perf_core_ids_.size(), get_available_ram_mb());
}

KofuEngine::~KofuEngine() {
    unload_model();
    unload_embed_model();
}

void KofuEngine::detect_snapdragon_topology() {
    std::string soc = read_sys_prop("ro.soc.model");
    std::string platform = read_sys_prop("ro.board.platform");
    std::string mfg = read_sys_prop("ro.soc.manufacturer");
    if (!soc.empty()) {
        soc_model_ = mfg.empty() ? soc : (mfg + " " + soc);
        if (!platform.empty()) soc_model_ += " (" + platform + ")";
    } else if (!platform.empty()) {
        soc_model_ = platform;
    } else {
        soc_model_ = "ARM64 Edge";
    }

#if defined(__aarch64__)
    const unsigned long hwcap = getauxval(AT_HWCAP);
    const unsigned long hwcap2 = getauxval(AT_HWCAP2);
#ifdef HWCAP_ASIMDDP
    has_dotprod_ = (hwcap & HWCAP_ASIMDDP) != 0;
#endif
#ifdef HWCAP_FPHP
    has_fp16_ = (hwcap & HWCAP_FPHP) != 0;
#endif
#ifdef HWCAP2_I8MM
    has_i8mm_ = (hwcap2 & HWCAP2_I8MM) != 0;
#endif
#endif

    // Clasificar núcleos por frecuencia máxima para identificar Kryo Prime + Performance
    unsigned int total_cores = std::thread::hardware_concurrency();
    if (total_cores == 0) total_cores = 8;

    std::vector<std::pair<int, long>> core_freqs;
    long max_freq = 0;
    long min_freq = -1;

    for (unsigned int cpu = 0; cpu < total_cores && cpu < 16; ++cpu) {
        std::string path = "/sys/devices/system/cpu/cpu" + std::to_string(cpu) + "/cpufreq/cpuinfo_max_freq";
        std::ifstream f(path);
        long freq = 0;
        if (f.is_open() && (f >> freq) && freq > 0) {
            core_freqs.push_back({static_cast<int>(cpu), freq});
            if (freq > max_freq) max_freq = freq;
            if (min_freq < 0 || freq < min_freq) min_freq = freq;
        }
    }

    perf_core_ids_.clear();
    if (!core_freqs.empty() && max_freq > min_freq) {
        // Todo núcleo por encima del 110% de la frecuencia Little pertenece a Performance/Prime
        const long threshold = min_freq + (max_freq - min_freq) / 4;
        for (const auto& [id, freq] : core_freqs) {
            if (freq >= threshold) {
                perf_core_ids_.push_back(id);
            }
        }
    }

    LOGI("Topología CPU: dotprod=%d fp16=%d i8mm=%d | Núcleos Prime/Perf detectados=%zu",
         has_dotprod_ ? 1 : 0, has_fp16_ ? 1 : 0, has_i8mm_ ? 1 : 0, perf_core_ids_.size());
}

size_t KofuEngine::get_process_rss_mb() {
    std::ifstream statm("/proc/self/statm");
    if (!statm.is_open()) return 0;
    size_t size = 0, resident = 0;
    statm >> size >> resident;
    long page_size_kb = sysconf(_SC_PAGESIZE) / 1024;
    return (resident * static_cast<size_t>(page_size_kb)) / 1024;
}

size_t KofuEngine::get_process_anon_rss_mb() {
    std::ifstream status("/proc/self/status");
    if (!status.is_open()) return get_process_rss_mb();
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind("RssAnon:", 0) == 0) {
            std::istringstream iss(line.substr(8));
            size_t kb = 0;
            if (iss >> kb) {
                return kb / 1024;
            }
        }
    }
    return get_process_rss_mb();
}

size_t KofuEngine::get_available_ram_mb() {
    std::ifstream meminfo("/proc/meminfo");
    if (!meminfo.is_open()) return 2048;
    std::string key;
    size_t value_kb = 0;
    std::string unit;
    while (meminfo >> key >> value_kb >> unit) {
        if (key == "MemAvailable:") {
            return value_kb / 1024;
        }
    }
    return 1536;
}

void KofuEngine::free_threadpools_locked() {
    if (lctx_) {
        llama_detach_threadpool(lctx_);
    }
    if (threadpool_) {
        ggml_threadpool_free(threadpool_);
        threadpool_ = nullptr;
    }
    if (threadpool_batch_) {
        ggml_threadpool_free(threadpool_batch_);
        threadpool_batch_ = nullptr;
    }
}

llama_context* KofuEngine::init_context_with_fallback(llama_model* model, HardwareTier tier) {
    if (!model) return nullptr;

    const std::string silicon_tier = KOFU_SILICON_TIER_STR;
    const bool is_7nm = (silicon_tier == "7NM");
    const bool is_frontier = (silicon_tier == "FRONTIER");
    const bool low_ram = low_ram_mode_.load() || is_7nm;

    unsigned int hardware_threads = std::thread::hardware_concurrency();
    int32_t max_threads_cap = is_frontier ? 6 : 4;
    int32_t optimal_threads = 4;
    if (perf_core_ids_.size() >= 3) {
        optimal_threads = static_cast<int32_t>(std::min<size_t>(max_threads_cap, perf_core_ids_.size()));
    } else if (hardware_threads > 0) {
        // En SoCs gama media/entrada 2+6, usar hasta 4 hilos para saturar ancho de banda LPDDR4X
        optimal_threads = static_cast<int32_t>(std::min<unsigned int>(max_threads_cap, std::max(2u, hardware_threads / 2)));
    }
    // Prefill paralelo: en SoCs gama baja / homogéneos (Serie A Exynos 850 / Helio G35) limitar a 4 hilos
    // para evitar starvation de UI y picos térmicos; en gama media/alta escalar hasta 8 hilos.
    const unsigned int batch_cap = (low_ram && perf_core_ids_.empty()) ? 4u : 8u;
    const int32_t batch_threads = (hardware_threads > 0)
        ? static_cast<int32_t>(std::min<unsigned int>(batch_cap, std::max<unsigned int>(static_cast<unsigned int>(optimal_threads), hardware_threads)))
        : optimal_threads;

    const size_t avail_mb = get_available_ram_mb();
    std::vector<uint32_t> ctx_candidates;
    if (low_ram) {
        if (avail_mb >= 1400) {
            ctx_candidates = {1536u, 1024u, 512u};
        } else if (avail_mb >= 900) {
            ctx_candidates = {1024u, 512u};
        } else {
            ctx_candidates = {512u};
        }
    } else if (is_frontier) {
        ctx_candidates = (avail_mb >= 2200) ? std::vector<uint32_t>{4096u, 2048u, 1024u} : std::vector<uint32_t>{2048u, 1024u};
    } else {
        if (avail_mb >= 2600) {
            ctx_candidates = {4096u, 2048u, 1024u};
        } else if (avail_mb >= 1300) {
            ctx_candidates = {2048u, 1024u};
        } else {
            ctx_candidates = {1024u};
        }
    }

    const uint32_t batch_size = low_ram ? (avail_mb >= 900 ? 256u : 128u) : (is_frontier ? 1024u : 512u);
    const uint32_t ubatch_size = low_ram ? (avail_mb >= 900 ? 128u : 64u) : (is_frontier ? 256u : 128u);

    char arch_buf[64] = {0};
    llama_model_meta_val_str(model, "general.architecture", arch_buf, sizeof(arch_buf));
    const bool is_gemma_or_iswa = (arch_buf[0] == '\0') ||
        contains_icase(arch_buf, "gemma") ||
        contains_icase(arch_buf, "iswa");

    const bool quantize_kv = low_ram || (avail_mb < 2200);
    struct KvTypePair {
        ggml_type type_k;
        ggml_type type_v;
        const char* label;
    };
    const std::vector<KvTypePair> kv_attempts = quantize_kv
        ? (avail_mb < 700
            ? std::vector<KvTypePair>{
                {GGML_TYPE_Q4_0, GGML_TYPE_Q4_0, "Q4_0/Q4_0"},
                {GGML_TYPE_Q8_0, GGML_TYPE_Q8_0, "Q8_0/Q8_0"},
                {GGML_TYPE_Q8_0, GGML_TYPE_F16,  "Q8_0/F16"},
                {GGML_TYPE_F16,  GGML_TYPE_F16,  "F16/F16"}
            }
            : std::vector<KvTypePair>{
                {GGML_TYPE_Q8_0, GGML_TYPE_Q8_0, "Q8_0/Q8_0"},
                {GGML_TYPE_Q8_0, GGML_TYPE_F16,  "Q8_0/F16"},
                {GGML_TYPE_F16,  GGML_TYPE_F16,  "F16/F16"}
            })
        : std::vector<KvTypePair>{
            {GGML_TYPE_F16,  GGML_TYPE_F16,  "F16/F16"}
        };

    for (uint32_t ctx_size : ctx_candidates) {
        for (const auto& kv_cfg : kv_attempts) {
            llama_context_params cparams = llama_context_default_params();
            cparams.n_ctx = ctx_size;
            cparams.n_batch = batch_size;
            cparams.n_ubatch = ubatch_size;
            cparams.n_threads = optimal_threads;
            cparams.n_threads_batch = batch_threads;
            cparams.type_k = kv_cfg.type_k;
            cparams.type_v = kv_cfg.type_v;
            // Requerido por llama_kv_cache_iswa para Context Shifting en Gemma
            cparams.swa_full = is_gemma_or_iswa;
            cparams.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
            cparams.offload_kqv = (tier != HardwareTier::CPU);
            cparams.op_offload = (tier != HardwareTier::CPU);

            llama_context* ctx = llama_init_from_model(model, cparams);
            if (ctx) {
                // Dual threadpool: decode en núcleos rápidos (optimal_threads) + prefill paralelo (batch_threads)
                free_threadpools_locked();
                if (perf_core_ids_.size() >= static_cast<size_t>(optimal_threads)) {
                    struct ggml_threadpool_params tp_params = ggml_threadpool_params_default(optimal_threads);
                    std::memset(tp_params.cpumask, 0, sizeof(tp_params.cpumask));
                    for (int core_id : perf_core_ids_) {
                        if (core_id >= 0 && core_id < GGML_MAX_N_THREADS) {
                            tp_params.cpumask[core_id] = true;
                        }
                    }
                    tp_params.strict_cpu = true;
                    tp_params.prio = GGML_SCHED_PRIO_HIGH;
                    threadpool_ = ggml_threadpool_new(&tp_params);
                }
                if (batch_threads > optimal_threads) {
                    struct ggml_threadpool_params tp_batch_params = ggml_threadpool_params_default(batch_threads);
                    tp_batch_params.strict_cpu = false;
                    tp_batch_params.prio = GGML_SCHED_PRIO_HIGH;
                    threadpool_batch_ = ggml_threadpool_new(&tp_batch_params);
                }
                if (threadpool_ || threadpool_batch_) {
                    llama_attach_threadpool(
                        ctx,
                        threadpool_,
                        threadpool_batch_ ? threadpool_batch_ : threadpool_
                    );
                }

                {
                    std::lock_guard<std::mutex> s_lock(stats_mutex_);
                    last_stats_.active_n_ctx = static_cast<int32_t>(ctx_size);
                    last_stats_.available_ram_mb = avail_mb;
                    last_stats_.soc_model = soc_model_;
                }

                LOGI("Contexto creado (%s): n_ctx=%u, kv=%s, swa_full=%d, decode_thr=%d, batch_thr=%d, availRAM=%zu MB",
                     tier_to_cstr(tier), ctx_size, kv_cfg.label, is_gemma_or_iswa ? 1 : 0, optimal_threads, batch_threads, avail_mb);
                return ctx;
            }
        }
        LOGW("Fallo al reservar n_ctx=%u en %s (RAM disp=%zu MB), degradando contexto...",
             ctx_size, tier_to_cstr(tier), avail_mb);
    }
    return nullptr;
}

bool KofuEngine::initialize_with_hardware_fallback(const std::string& source_path, const std::string& display_name) {
    const auto load_t0 = std::chrono::steady_clock::now();
    // Clasificar dispositivos ggml registrados en tiempo de ejecución
    std::vector<ggml_backend_dev_t> npu_devs;
    std::vector<ggml_backend_dev_t> gpu_devs;
    ggml_backend_dev_t cpu_dev = nullptr;

    const size_t dev_count = ggml_backend_dev_count();
    for (size_t i = 0; i < dev_count; ++i) {
        ggml_backend_dev_t dev = ggml_backend_dev_get(i);
        if (!dev) continue;
        ggml_backend_dev_props props{};
        ggml_backend_dev_get_props(dev, &props);
        const char* reg_name = ggml_backend_reg_name(ggml_backend_dev_backend_reg(dev));

        const bool is_hexagon = (reg_name && std::strcmp(reg_name, "HTP") == 0) ||
            contains_icase(props.name, "Hexagon") ||
            contains_icase(props.description, "Hexagon");

        if (is_hexagon || props.type == GGML_BACKEND_DEVICE_TYPE_ACCEL) {
            npu_devs.push_back(dev);
        } else if (props.type == GGML_BACKEND_DEVICE_TYPE_GPU || props.type == GGML_BACKEND_DEVICE_TYPE_IGPU) {
            gpu_devs.push_back(dev);
        } else if (props.type == GGML_BACKEND_DEVICE_TYPE_CPU && !cpu_dev) {
            cpu_dev = dev;
        }
    }

    std::vector<HardwareTier> tier_order;
    switch (preferred_accelerator_) {
        case PreferredAccelerator::FORCE_NPU:
            tier_order = {HardwareTier::NPU, HardwareTier::CPU};
            break;
        case PreferredAccelerator::FORCE_GPU:
            tier_order = {HardwareTier::GPU, HardwareTier::CPU};
            break;
        case PreferredAccelerator::FORCE_CPU:
            tier_order = {HardwareTier::CPU};
            break;
        case PreferredAccelerator::AUTO_NPU_GPU_CPU:
        default:
            // Priorizar GPU primero; si no está disponible o es incompatible, degradar a NPU/CPU
            tier_order = {HardwareTier::GPU, HardwareTier::NPU, HardwareTier::CPU};
            break;
    }

    for (HardwareTier tier : tier_order) {
        std::vector<ggml_backend_dev_t> selected_devs;
        if (tier == HardwareTier::NPU) {
            if (npu_devs.empty()) {
                LOGI("Cascada Fallback: Tier NPU no disponible en esta compilación/dispositivo; degradando a CPU.");
                continue;
            }
            selected_devs.push_back(npu_devs.front());
            if (cpu_dev) selected_devs.push_back(cpu_dev);
            selected_devs.push_back(nullptr);
        } else if (tier == HardwareTier::GPU) {
            if (gpu_devs.empty()) {
                LOGI("Cascada Fallback: GPU (Vulkan/OpenCL) no disponible o no compatible; usando CPU ARMv8 SIMD.");
                continue;
            }
            selected_devs.push_back(gpu_devs.front());
            if (cpu_dev) selected_devs.push_back(cpu_dev);
            selected_devs.push_back(nullptr);
        } else {
            if (cpu_dev) {
                selected_devs.push_back(cpu_dev);
                selected_devs.push_back(nullptr);
            }
        }

        const size_t avail_mb = get_available_ram_mb();
        llama_model_params mparams = llama_model_default_params();
        mparams.devices = selected_devs.empty() ? nullptr : selected_devs.data();
        mparams.n_gpu_layers = (tier == HardwareTier::CPU) ? 0 : -1;
        mparams.load_mode = (tier == HardwareTier::CPU) ? LLAMA_LOAD_MODE_MMAP : LLAMA_LOAD_MODE_AUTO;
        // En presupuesto <= 2 GB evitar repacking en RssAnon para mantener mmap zero-copy evictable
        mparams.use_extra_bufts = !low_ram_mode_.load() && (avail_mb >= 2200);

        llama_model* candidate_model = llama_model_load_from_file(source_path.c_str(), mparams);
        if (!candidate_model) {
            LOGW("Fallo cargando modelo en tier %s, intentando siguiente nivel...", tier_to_cstr(tier));
            continue;
        }

        llama_context* candidate_ctx = init_context_with_fallback(candidate_model, tier);
        if (!candidate_ctx) {
            LOGW("Fallo inicializando contexto en tier %s, liberando y degradando...", tier_to_cstr(tier));
            llama_model_free(candidate_model);
            continue;
        }

        lmodel_ = candidate_model;
        lctx_ = candidate_ctx;
        active_tier_ = tier;
        is_model_loaded_.store(true);

        std::string hw_desc = std::string("llama.cpp ") + tier_to_cstr(tier) + " (" + soc_model_ + ")";
        {
            std::lock_guard<std::mutex> s_lock(stats_mutex_);
            loaded_path_ = source_path;
            model_name_ = display_name;
            last_stats_.hardware_tier = tier_to_cstr(tier);
            last_stats_.backend_name = hw_desc;
            last_stats_.soc_model = soc_model_;
        }

        LOGI("Modelo cargado en Tier %s (%s): %s",
             tier_to_cstr(tier), soc_model_.c_str(), display_name.c_str());
        warmup_system_prompt_locked();
        const double load_ms = std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now() - load_t0
        ).count();
        {
            std::lock_guard<std::mutex> s_lock(stats_mutex_);
            last_stats_.load_ms = load_ms;
            last_stats_.memory_rss_mb = get_process_rss_mb();
            last_stats_.anon_rss_mb = get_process_anon_rss_mb();
            last_stats_.available_ram_mb = get_available_ram_mb();
        }
        LOGI("Carga completa | model=%s | load_ms=%.0f | rss=%zu MB | anon=%zu MB | availRAM=%zu MB",
             display_name.c_str(), load_ms, last_stats_.memory_rss_mb, last_stats_.anon_rss_mb, last_stats_.available_ram_mb);
        return true;
    }

    return false;
}

namespace {
std::pair<PromptStyle, std::string> resolve_prompt_style_and_system(
    const std::string& model_name,
    const std::string& raw_system_prompt
) {
    std::string lower_name = model_name;
    std::transform(lower_name.begin(), lower_name.end(), lower_name.begin(), ::tolower);

    // En dev-test (100% MIT, 0% Gemma por defecto), ChatML es el estilo predeterminado para Kaze y Aura
    PromptStyle style = PromptStyle::CHATML;
    if (lower_name.find("gemma4") != std::string::npos || lower_name.find("gemma-4") != std::string::npos) {
        style = PromptStyle::GEMMA_4;
    } else if (lower_name.find("gemma") != std::string::npos) {
        style = PromptStyle::GEMMA;
    } else if (lower_name.find("llama3") != std::string::npos || lower_name.find("llama-3") != std::string::npos) {
        style = PromptStyle::LLAMA3;
    } else if (lower_name.find("kaze") != std::string::npos ||
               lower_name.find("aura") != std::string::npos ||
               lower_name.find("chatml") != std::string::npos ||
               lower_name.find("qwen") != std::string::npos ||
               lower_name.find("smol") != std::string::npos ||
               lower_name.find("lfm") != std::string::npos) {
        style = PromptStyle::CHATML;
    }

    std::string effective_sys = raw_system_prompt;
    if (!effective_sys.empty() &&
        lower_name.find("qwen3") != std::string::npos &&
        effective_sys.find("/no_think") == std::string::npos &&
        effective_sys.find("/think") == std::string::npos) {
        effective_sys += "\n/no_think";
    }
    return {style, effective_sys};
}
} // namespace

void KofuEngine::warmup_system_prompt_locked() {
    if (!lmodel_ || !lctx_) return;
    std::string sys_prompt;
    std::string current_name;
    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        sys_prompt = gen_config_.system_prompt;
        current_name = model_name_;
    }
    if (sys_prompt.empty()) return;
    const auto [prompt_style, effective_sys] = resolve_prompt_style_and_system(current_name, sys_prompt);

    const std::string sys_only = build_system_prefix(effective_sys, prompt_style);
    if (sys_only.empty()) return;
    const llama_vocab* vocab = llama_model_get_vocab(lmodel_);
    if (!vocab) return;

    std::vector<llama_token> sys_toks(sys_only.size() + 16);
    int32_t n_sys = llama_tokenize(
        vocab, sys_only.c_str(), static_cast<int32_t>(sys_only.length()),
        sys_toks.data(), static_cast<int32_t>(sys_toks.size()), true, true
    );
    if (n_sys <= 3) return;
    // Excluir último token (\n\n) para evitar fusión BPE con la primera palabra del usuario
    const int32_t prefix_len = std::min<int32_t>(n_sys - 1, static_cast<int32_t>(llama_n_ctx(lctx_)) / 4);
    if (prefix_len <= 2) return;
    sys_toks.resize(prefix_len);

    llama_memory_t mem = llama_get_memory(lctx_);
    if (mem) llama_memory_clear(mem, true);
    kv_cache_tokens_.clear();

    llama_batch batch = llama_batch_init(prefix_len, 0, 1);
    for (int32_t i = 0; i < prefix_len; ++i) {
        batch.token[i] = sys_toks[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 0;
    }
    batch.n_tokens = prefix_len;

    if (llama_decode(lctx_, batch) == 0) {
        kv_cache_tokens_ = std::move(sys_toks);
        LOGI("KV Warmup completado: %d tokens de System Prompt pre-cacheados", prefix_len);
    } else if (mem) {
        llama_memory_clear(mem, true);
    }
    llama_batch_free(batch);
}

void KofuEngine::unload_model_locked() {
    cancel_generation();
    is_model_loaded_.store(false);
    active_tier_ = HardwareTier::NONE;
    kv_cache_tokens_.clear();

    free_threadpools_locked();
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
    moe_pager_.detach();
    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        loaded_path_.clear();
        model_name_.clear();
        last_stats_.hardware_tier = "NONE";
    }
}

void KofuEngine::unload_model() {
    cancel_generation();
    std::lock_guard<std::mutex> lock(engine_mutex_);
    unload_model_locked();
    LOGI("Modelo y contexto llama.cpp liberados correctamente");
}

bool KofuEngine::load_model(const std::string& filepath) {
    cancel_generation();
    std::lock_guard<std::mutex> lock(engine_mutex_);
    unload_model_locked();

    if (!loader_->load(filepath)) {
        LOGE("Fallo en validación binaria para: %s", filepath.c_str());
        return false;
    }

    // Adjuntar paginador jerárquico UFS MoE (Hot/Warm/Cold) sobre el archivo en almacenamiento interno
    moe_pager_.attach_model_file(filepath, 240);

    if (!initialize_with_hardware_fallback(filepath, loader_->get_metadata().filename)) {
        LOGE("Fallo en todos los tiers de hardware (NPU->GPU->CPU) para %s", filepath.c_str());
        moe_pager_.detach();
        loader_->unload();
        return false;
    }
    return true;
}

bool KofuEngine::load_model_fd(int fd, size_t size, const std::string& name) {
    cancel_generation();
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

    std::string proc_fd_path = "/proc/self/fd/" + std::to_string(fd);
    if (!initialize_with_hardware_fallback(proc_fd_path, name)) {
        LOGE("Fallo cargando FD %d en cascada NPU->GPU->CPU", fd);
        unload_model_locked();
        return false;
    }
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
    return 2; // Motor local C++ (llama.cpp)
}

std::string KofuEngine::get_active_hardware_tier() const {
    return tier_to_cstr(active_tier_);
}

void KofuEngine::set_preferred_accelerator(int mode) {
    std::lock_guard<std::mutex> lock(engine_mutex_);
    switch (mode) {
        case 1: preferred_accelerator_ = PreferredAccelerator::FORCE_NPU; break;
        case 2: preferred_accelerator_ = PreferredAccelerator::FORCE_GPU; break;
        case 3: preferred_accelerator_ = PreferredAccelerator::FORCE_CPU; break;
        default: preferred_accelerator_ = PreferredAccelerator::AUTO_NPU_GPU_CPU; break;
    }
}

void KofuEngine::set_low_ram_budget(bool enabled, size_t max_rss_mb, bool debug_governor_enabled) {
    low_ram_mode_.store(enabled);
    debug_ram_governor_enabled_.store(debug_governor_enabled);
    max_rss_budget_mb_.store(std::max<size_t>(512, max_rss_mb));
    LOGI("LowRamBudget configurado: enabled=%d, max_rss=%zu MB, debug_governor=%d",
         enabled ? 1 : 0, max_rss_budget_mb_.load(), debug_governor_enabled ? 1 : 0);
}

void KofuEngine::trim_memory(int level) {
    // Niveles moderados/bajos/UI oculta (5, 10, 20): liberar embedder y recortar caché Warm MoE
    unload_embed_model();
    const bool is_critical_trim = (level == 15) || (level >= 40);
    moe_pager_.trim_warm_cache(is_critical_trim ? 0.0 : 0.5);
    if (!is_critical_trim) {
        LOGI("MemoryGuard: trim_memory nivel=%d, embedder liberado; generación activa preservada (RAM disp: %zu MB)",
             level, get_available_ram_mb());
        return;
    }

    if (is_generating_.load()) {
        cancel_generation();
        return;
    }
    std::unique_lock<std::mutex> lock(engine_mutex_, std::try_to_lock);
    if (!lock.owns_lock() || !lctx_) return;

    llama_memory_t mem = llama_get_memory(lctx_);
    if (mem) {
        llama_memory_clear(mem, true);
    }
    kv_cache_tokens_.clear();
    LOGW("MemoryGuard: KV cache liberado tras evento crítico trim_memory=%d (RAM disp: %zu MB)", level, get_available_ram_mb());
}

void KofuEngine::unload_embed_model_locked() {
    is_embed_loaded_.store(false);
    embed_dim_ = 0;
    if (embed_ctx_) {
        llama_free(embed_ctx_);
        embed_ctx_ = nullptr;
    }
    if (embed_model_) {
        llama_model_free(embed_model_);
        embed_model_ = nullptr;
    }
}

void KofuEngine::unload_embed_model() {
    std::lock_guard<std::mutex> lock(embed_mutex_);
    unload_embed_model_locked();
}

bool KofuEngine::is_embed_model_loaded() const {
    return is_embed_loaded_.load();
}

int32_t KofuEngine::get_embed_dim() const {
    std::lock_guard<std::mutex> lock(embed_mutex_);
    return embed_dim_;
}

bool KofuEngine::load_embed_model(const std::string& filepath) {
    std::lock_guard<std::mutex> lock(embed_mutex_);
    unload_embed_model_locked();

    llama_model_params mparams = llama_model_default_params();
    mparams.n_gpu_layers = 0;
    mparams.load_mode = LLAMA_LOAD_MODE_MMAP;

    embed_model_ = llama_model_load_from_file(filepath.c_str(), mparams);
    if (!embed_model_) {
        LOGE("No se pudo cargar micro-embedder GGUF: %s", filepath.c_str());
        return false;
    }

    llama_context_params cparams = llama_context_default_params();
    cparams.n_ctx = 256;
    cparams.n_batch = 128;
    cparams.n_ubatch = 128;
    unsigned int hw_threads = std::thread::hardware_concurrency();
    int32_t n_threads = !perf_core_ids_.empty()
        ? static_cast<int32_t>(std::min<size_t>(4, std::max<size_t>(2, perf_core_ids_.size())))
        : static_cast<int32_t>(std::min<unsigned int>(4, std::max(2u, hw_threads / 2)));
    cparams.n_threads = n_threads;
    cparams.n_threads_batch = n_threads;
    cparams.embeddings = true;
    cparams.pooling_type = LLAMA_POOLING_TYPE_UNSPECIFIED;

    embed_ctx_ = llama_init_from_model(embed_model_, cparams);
    if (!embed_ctx_) {
        LOGE("No se pudo crear contexto de embeddings para %s", filepath.c_str());
        unload_embed_model_locked();
        return false;
    }

    embed_dim_ = llama_model_n_embd(embed_model_);
    is_embed_loaded_.store(embed_dim_ > 0);
    LOGI("Micro-embedder GGUF listo: dim=%d, pooling=%d, path=%s",
         embed_dim_, static_cast<int>(llama_pooling_type(embed_ctx_)), filepath.c_str());
    return is_embed_loaded_.load();
}

std::vector<int8_t> KofuEngine::embed_text_int8(const std::string& text) {
    std::lock_guard<std::mutex> lock(embed_mutex_);
    if (!embed_model_ || !embed_ctx_ || embed_dim_ <= 0 || text.empty()) {
        return {};
    }

    const llama_vocab* vocab = llama_model_get_vocab(embed_model_);
    if (!vocab) return {};

    std::vector<llama_token> tokens(text.size() + 32);
    int32_t n_tokens = llama_tokenize(
        vocab,
        text.c_str(),
        static_cast<int32_t>(text.length()),
        tokens.data(),
        static_cast<int32_t>(tokens.size()),
        true,
        true
    );
    if (n_tokens < 0) {
        tokens.resize(-n_tokens);
        n_tokens = llama_tokenize(
            vocab,
            text.c_str(),
            static_cast<int32_t>(text.length()),
            tokens.data(),
            static_cast<int32_t>(tokens.size()),
            true,
            true
        );
    }
    if (n_tokens <= 0) return {};

    const int32_t max_embed_ctx = static_cast<int32_t>(llama_n_ctx(embed_ctx_));
    if (n_tokens > max_embed_ctx) {
        n_tokens = max_embed_ctx;
    }
    tokens.resize(n_tokens);

    llama_memory_t mem = llama_get_memory(embed_ctx_);
    if (mem) {
        llama_memory_clear(mem, true);
    }

    llama_batch batch = llama_batch_init(n_tokens, 0, 1);
    for (int32_t i = 0; i < n_tokens; ++i) {
        batch.token[i] = tokens[i];
        batch.pos[i] = i;
        batch.n_seq_id[i] = 1;
        batch.seq_id[i][0] = 0;
        batch.logits[i] = 1;
    }
    batch.n_tokens = n_tokens;

    const int rc = llama_decode(embed_ctx_, batch);
    llama_batch_free(batch);
    if (rc != 0) {
        LOGE("llama_decode falló en micro-embedder (rc=%d)", rc);
        return {};
    }

    const float* emb = nullptr;
    if (llama_pooling_type(embed_ctx_) != LLAMA_POOLING_TYPE_NONE) {
        emb = llama_get_embeddings_seq(embed_ctx_, 0);
    }
    if (!emb) {
        emb = llama_get_embeddings_ith(embed_ctx_, -1);
    }
    if (!emb) {
        emb = llama_get_embeddings(embed_ctx_);
    }
    if (!emb) return {};

    double norm_sq = 0.0;
    for (int32_t i = 0; i < embed_dim_; ++i) {
        norm_sq += static_cast<double>(emb[i]) * static_cast<double>(emb[i]);
    }
    const float inv_norm = (norm_sq > 1e-12) ? static_cast<float>(1.0 / std::sqrt(norm_sq)) : 0.0f;

    float max_abs = 0.0f;
    std::vector<float> normalized(embed_dim_);
    for (int32_t i = 0; i < embed_dim_; ++i) {
        const float v = emb[i] * inv_norm;
        normalized[i] = v;
        max_abs = std::max(max_abs, std::fabs(v));
    }

    std::vector<int8_t> out(embed_dim_, 0);
    if (max_abs > 1e-9f) {
        const float scale = 127.0f / max_abs;
        for (int32_t i = 0; i < embed_dim_; ++i) {
            const float q = std::clamp(std::round(normalized[i] * scale), -127.0f, 127.0f);
            out[i] = static_cast<int8_t>(q);
        }
    }
    return out;
}

std::vector<float> KofuEngine::score_int8_cosine(
    const std::vector<int8_t>& query_vec,
    const int8_t* corpus_flat,
    int32_t num_docs,
    int32_t dim
) {
    std::vector<float> scores(static_cast<size_t>(std::max(0, num_docs)), 0.0f);
    if (!corpus_flat || num_docs <= 0 || dim <= 0 || static_cast<int32_t>(query_vec.size()) != dim) {
        return scores;
    }

    const int8_t* q = query_vec.data();
    int64_t q_norm_sq = 0;
#if defined(__aarch64__)
    int32x4_t q_norm_acc = vdupq_n_s32(0);
    int32_t i_q = 0;
    for (; i_q + 16 <= dim; i_q += 16) {
        int8x16_t vq = vld1q_s8(q + i_q);
        int16x8_t prod_lo = vmull_s8(vget_low_s8(vq), vget_low_s8(vq));
        int16x8_t prod_hi = vmull_s8(vget_high_s8(vq), vget_high_s8(vq));
        q_norm_acc = vpadalq_s16(q_norm_acc, prod_lo);
        q_norm_acc = vpadalq_s16(q_norm_acc, prod_hi);
    }
    q_norm_sq = static_cast<int64_t>(vaddvq_s32(q_norm_acc));
    for (; i_q < dim; ++i_q) {
        q_norm_sq += static_cast<int64_t>(q[i_q]) * static_cast<int64_t>(q[i_q]);
    }
#else
    for (int32_t i = 0; i < dim; ++i) {
        q_norm_sq += static_cast<int64_t>(q[i]) * static_cast<int64_t>(q[i]);
    }
#endif
    if (q_norm_sq <= 0) return scores;
    const double q_norm = std::sqrt(static_cast<double>(q_norm_sq));

    for (int32_t d = 0; d < num_docs; ++d) {
        const int8_t* doc = corpus_flat + static_cast<size_t>(d) * static_cast<size_t>(dim);
        int64_t dot = 0;
        int64_t d_norm_sq = 0;

#if defined(__aarch64__)
        int32x4_t dot_acc = vdupq_n_s32(0);
        int32x4_t d_norm_acc = vdupq_n_s32(0);
        int32_t i = 0;
        for (; i + 16 <= dim; i += 16) {
            int8x16_t vq = vld1q_s8(q + i);
            int8x16_t vd = vld1q_s8(doc + i);

            int16x8_t dot_lo = vmull_s8(vget_low_s8(vq), vget_low_s8(vd));
            int16x8_t dot_hi = vmull_s8(vget_high_s8(vq), vget_high_s8(vd));
            dot_acc = vpadalq_s16(dot_acc, dot_lo);
            dot_acc = vpadalq_s16(dot_acc, dot_hi);

            int16x8_t dn_lo = vmull_s8(vget_low_s8(vd), vget_low_s8(vd));
            int16x8_t dn_hi = vmull_s8(vget_high_s8(vd), vget_high_s8(vd));
            d_norm_acc = vpadalq_s16(d_norm_acc, dn_lo);
            d_norm_acc = vpadalq_s16(d_norm_acc, dn_hi);
        }
        dot = static_cast<int64_t>(vaddvq_s32(dot_acc));
        d_norm_sq = static_cast<int64_t>(vaddvq_s32(d_norm_acc));
        for (; i < dim; ++i) {
            dot += static_cast<int64_t>(q[i]) * static_cast<int64_t>(doc[i]);
            d_norm_sq += static_cast<int64_t>(doc[i]) * static_cast<int64_t>(doc[i]);
        }
#else
        for (int32_t i = 0; i < dim; ++i) {
            dot += static_cast<int64_t>(q[i]) * static_cast<int64_t>(doc[i]);
            d_norm_sq += static_cast<int64_t>(doc[i]) * static_cast<int64_t>(doc[i]);
        }
#endif
        if (d_norm_sq > 0) {
            const double denom = q_norm * std::sqrt(static_cast<double>(d_norm_sq));
            scores[d] = static_cast<float>(static_cast<double>(dot) / denom);
        }
    }
    return scores;
}

void KofuEngine::set_generation_config(const GenerationConfig& config) {
    std::lock_guard<std::mutex> lock(stats_mutex_);
    gen_config_.temperature = std::clamp(config.temperature, 0.0f, 2.0f);
    gen_config_.top_p = std::clamp(config.top_p, 0.05f, 1.0f);
    gen_config_.repeat_penalty = std::clamp(config.repeat_penalty, 1.0f, 2.0f);
    gen_config_.max_tokens = std::clamp(config.max_tokens, 8, 4096);
    if (!config.system_prompt.empty()) {
        gen_config_.system_prompt = config.system_prompt;
    }
}

namespace {

size_t suffix_prefix_overlap(const std::string& text, const std::string& tag) {
    const size_t max_check = std::min(text.size(), tag.empty() ? 0 : tag.size() - 1);
    for (size_t len = max_check; len > 0; --len) {
        if (text.compare(text.size() - len, len, tag, 0, len) == 0) {
            return len;
        }
    }
    return 0;
}

class ThinkTagStreamFilter {
public:
    template <typename EmitFn>
    void push(const std::string& valid_utf8, EmitFn&& emit) {
        buffer_ += valid_utf8;
        process(false, std::forward<EmitFn>(emit));
    }

    template <typename EmitFn>
    void finish(EmitFn&& emit) {
        process(true, std::forward<EmitFn>(emit));
    }

private:
    static constexpr const char* kOpenTag = "<think>";
    static constexpr const char* kCloseTag = "</think>";
    std::string buffer_;
    bool in_think_{false};
    bool trim_leading_ws_{false};

    template <typename EmitFn>
    void emit_clean(std::string chunk, EmitFn&& emit) {
        if (trim_leading_ws_) {
            const size_t first = chunk.find_first_not_of(" \t\r\n");
            if (first == std::string::npos) return;
            chunk.erase(0, first);
            trim_leading_ws_ = false;
        }
        if (!chunk.empty()) emit(chunk);
    }

    template <typename EmitFn>
    void process(bool flush, EmitFn&& emit) {
        const std::string open_tag(kOpenTag);
        const std::string close_tag(kCloseTag);
        while (!buffer_.empty()) {
            if (!in_think_) {
                const size_t pos = buffer_.find(open_tag);
                if (pos != std::string::npos) {
                    if (pos > 0) emit_clean(buffer_.substr(0, pos), emit);
                    buffer_.erase(0, pos + open_tag.size());
                    in_think_ = true;
                    continue;
                }
                if (flush) {
                    emit_clean(std::move(buffer_), emit);
                    buffer_.clear();
                    break;
                }
                const size_t keep = suffix_prefix_overlap(buffer_, open_tag);
                const size_t emit_len = buffer_.size() - keep;
                if (emit_len > 0) {
                    emit_clean(buffer_.substr(0, emit_len), emit);
                    buffer_.erase(0, emit_len);
                }
                break;
            } else {
                const size_t pos = buffer_.find(close_tag);
                if (pos != std::string::npos) {
                    buffer_.erase(0, pos + close_tag.size());
                    in_think_ = false;
                    trim_leading_ws_ = true;
                    continue;
                }
                if (flush) {
                    buffer_.clear();
                    break;
                }
                const size_t keep = suffix_prefix_overlap(buffer_, close_tag);
                if (buffer_.size() > keep) {
                    buffer_.erase(0, buffer_.size() - keep);
                }
                break;
            }
        }
    }
};

}  // namespace

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

    GenerationConfig cfg;
    std::string current_name;
    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        cfg = gen_config_;
        current_name = model_name_;
    }

    // 1. Determinar plantilla según el nombre del modelo (Gemma / Gemma-4 / ChatML incl. Qwen y LFM2)
    const auto [prompt_style, effective_sys] = resolve_prompt_style_and_system(current_name, cfg.system_prompt);

    std::string formatted_prompt = PromptFormatter::format_multi_turn_prompt(
        history,
        effective_sys,
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

    const int32_t max_context = static_cast<int32_t>(llama_n_ctx(lctx_));
    const int32_t reserved_generation = std::min(384, max_context / 4);
    const int32_t max_prompt_budget = std::max(128, max_context - reserved_generation);

    // Calcular tokens del prefijo del System Prompt para anclar n_keep en poda y Context Shifting
    size_t system_prefix_tokens = 16;
    if (!effective_sys.empty()) {
        const std::string sys_only = build_system_prefix(effective_sys, prompt_style);
        if (!sys_only.empty()) {
            std::vector<llama_token> sys_toks(sys_only.size() + 16);
            int32_t n_sys = llama_tokenize(
                vocab,
                sys_only.c_str(),
                static_cast<int32_t>(sys_only.length()),
                sys_toks.data(),
                static_cast<int32_t>(sys_toks.size()),
                true,
                true
            );
            if (n_sys > 2) {
                system_prefix_tokens = static_cast<size_t>(std::max<int32_t>(8, n_sys - 1));
            }
        }
    }
    system_prefix_tokens = std::min(system_prefix_tokens, tokens_list.size() / 2);

    if (static_cast<int32_t>(tokens_list.size()) > max_prompt_budget) {
        LOGW("Prompt excede presupuesto de contexto (%zu > %d), aplicando poda segura...",
             tokens_list.size(), max_prompt_budget);
        const size_t keep_prefix = std::max<size_t>(16, system_prefix_tokens);
        const size_t excess = tokens_list.size() - static_cast<size_t>(max_prompt_budget);
        if (keep_prefix + excess < tokens_list.size()) {
            tokens_list.erase(
                tokens_list.begin() + static_cast<std::ptrdiff_t>(keep_prefix),
                tokens_list.begin() + static_cast<std::ptrdiff_t>(keep_prefix + excess)
            );
        } else {
            tokens_list.erase(tokens_list.begin(), tokens_list.begin() + static_cast<std::ptrdiff_t>(excess));
        }
        kv_cache_tokens_.clear();
    }

    // 3. Reutilización incremental de KV Cache (Prefix Caching)
    size_t n_past = 0;
    while (n_past < kv_cache_tokens_.size() &&
           n_past + 1 < tokens_list.size() &&
           kv_cache_tokens_[n_past] == tokens_list[n_past]) {
        ++n_past;
    }

    llama_memory_t mem = llama_get_memory(lctx_);
    if (n_past == 0) {
        llama_memory_clear(mem, true);
        kv_cache_tokens_.clear();
    } else if (n_past < kv_cache_tokens_.size()) {
        if (!llama_memory_seq_rm(mem, 0, static_cast<llama_pos>(n_past), -1)) {
            llama_memory_clear(mem, true);
            kv_cache_tokens_.clear();
            n_past = 0;
        } else {
            kv_cache_tokens_.resize(n_past);
        }
    }

    LOGI("Inferencia | tokens_prompt=%zu | kv_reused=%zu | temp=%.2f | max_tok=%u | tier=%s",
         tokens_list.size(), n_past, cfg.temperature,
         static_cast<unsigned int>(cfg.max_tokens), tier_to_cstr(active_tier_));

    // 4. Inicializar cadena de muestreo (Sampler Chain)
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    llama_sampler* smpl = llama_sampler_chain_init(sparams);

    if (cfg.temperature <= 0.01f) {
        if (cfg.repeat_penalty > 1.0f) {
            llama_sampler_chain_add(
                smpl,
                llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 32, cfg.repeat_penalty, 0.0f, 0.0f)
            );
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_greedy());
    } else {
        llama_sampler_chain_add(smpl, llama_sampler_init_top_k(40));
        llama_sampler_chain_add(smpl, llama_sampler_init_top_p(cfg.top_p, 1));
        if (cfg.repeat_penalty > 1.0f) {
            llama_sampler_chain_add(
                smpl,
                llama_sampler_init_penalties(llama_vocab_n_tokens(vocab), 32, cfg.repeat_penalty, 0.0f, 0.0f)
            );
        }
        llama_sampler_chain_add(smpl, llama_sampler_init_temp(cfg.temperature));
        llama_sampler_chain_add(smpl, llama_sampler_init_dist(static_cast<uint32_t>(std::time(nullptr))));
    }

    const size_t penalty_seed_start = (tokens_list.size() > 32) ? (tokens_list.size() - 32) : 0;
    for (size_t i = penalty_seed_start; i < tokens_list.size(); ++i) {
        llama_sampler_accept(smpl, tokens_list[i]);
    }

    // 5. Preflight de memoria <= 2 GB y Prefill en micro-lotes
    const bool strict_budget = low_ram_mode_.load() || (std::string(KOFU_SILICON_TIER_STR) == "7NM");
    const size_t max_rss_budget = max_rss_budget_mb_.load();
    if (strict_budget && is_embed_loaded_.load()) {
        const size_t pre_avail = get_available_ram_mb();
        const size_t pre_rss = get_process_rss_mb();
        if (pre_avail < 240 || (pre_rss > 0 && pre_rss + 80 >= max_rss_budget)) {
            LOGW("Preflight <=2GB: liberando micro-embedder antes de prefill (avail=%zu MB, rss=%zu MB)",
                 pre_avail, pre_rss);
            unload_embed_model();
        }
    }

    const int32_t prefill_batch_size = std::clamp<int32_t>(
        static_cast<int32_t>(llama_n_batch(lctx_)), 128, 256
    );
    llama_batch batch = llama_batch_init(prefill_batch_size, 0, 1);
    bool prompt_interrupted = false;

    for (size_t i = n_past; i < tokens_list.size(); i += static_cast<size_t>(prefill_batch_size)) {
        if (is_cancelled_.load()) {
            prompt_interrupted = true;
            break;
        }

        const int32_t n_eval = static_cast<int32_t>(
            std::min(static_cast<size_t>(prefill_batch_size), tokens_list.size() - i)
        );
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
            llama_memory_clear(mem, true);
            kv_cache_tokens_.clear();
            token_callback("\n[Error de decodificación en prompt]");
            llama_batch_free(batch);
            llama_sampler_free(smpl);
            is_generating_.store(false);
            return;
        }

        kv_cache_tokens_.insert(
            kv_cache_tokens_.end(),
            tokens_list.begin() + static_cast<std::ptrdiff_t>(i),
            tokens_list.begin() + static_cast<std::ptrdiff_t>(i + n_eval)
        );
    }

    if (prompt_interrupted || is_cancelled_.load()) {
        token_callback("\n[Generación cancelada]");
        llama_batch_free(batch);
        llama_sampler_free(smpl);
        is_generating_.store(false);
        return;
    }

    // 6. Bucle de generación autoregresiva con Context Shifting ISWA-safe, Gobernador Debug 70-80% y LMK Guard
    auto decode_start_time = std::chrono::steady_clock::now();
    const double prefill_ms = std::chrono::duration<double, std::milli>(decode_start_time - start_time).count();
    const bool debug_governor = debug_ram_governor_enabled_.load();
    const size_t soft_70_rss_mb = (max_rss_budget * 70) / 100;
    const size_t hard_80_rss_mb = (max_rss_budget * 80) / 100;
    std::string gov_state = debug_governor ? "NORMAL" : "OFF";
    uint32_t throttle_delay_ms = 0;

    llama_pos n_cur = static_cast<llama_pos>(tokens_list.size());
    const uint32_t max_generated_tokens = static_cast<uint32_t>(
        std::clamp(cfg.max_tokens, 8, max_context)
    );
    uint32_t token_count = 0;
    bool low_ram_triggered = false;

    std::string utf8_accumulator;
    ThinkTagStreamFilter think_filter;
    auto emit_utf8_safe = [&](const std::string& piece, bool flush = false) {
        utf8_accumulator += piece;
        if (flush) {
            if (!utf8_accumulator.empty()) {
                think_filter.push(utf8_accumulator, token_callback);
                utf8_accumulator.clear();
            }
            think_filter.finish(token_callback);
            return;
        }
        size_t incomplete = count_incomplete_utf8_trailing_bytes(utf8_accumulator);
        if (incomplete < utf8_accumulator.length()) {
            size_t valid_len = utf8_accumulator.length() - incomplete;
            think_filter.push(utf8_accumulator.substr(0, valid_len), token_callback);
            utf8_accumulator.erase(0, valid_len);
        }
    };

    char piece_buf[256];

    while (token_count < max_generated_tokens) {
        if (is_cancelled_.load()) {
            emit_utf8_safe("", true);
            token_callback("\n[Generación cancelada]");
            break;
        }

        // LMK Circuit Breaker + Gobernador Térmico/RAM cada 8 tokens (activo en Debug y Release)
        if (token_count > 0 && ((token_count % 8 == 0) || throttle_delay_ms > 0 || thermal_throttle_ms_.load() > 0)) {
            const size_t min_safe_ram = strict_budget ? 150 : 120;
            const size_t max_safe_anon_mb = strict_budget
                ? std::max<size_t>(300, std::min<size_t>(650, max_rss_budget / 2))
                : 950;
            size_t avail_mb = get_available_ram_mb();
            size_t anon_mb = get_process_anon_rss_mb();
            size_t rss_mb = get_process_rss_mb();

            const uint32_t thm_delay = moe_pager_.apply_thermal_and_ram_guard(
                thermal_status_.load(), rss_mb, max_rss_budget
            );
            thermal_throttle_ms_.store(thm_delay);

            if (debug_governor) {
                if (rss_mb >= hard_80_rss_mb || avail_mb < 220) {
                    if (is_embed_loaded_.load()) unload_embed_model();
                    if (gov_state != "THROTTLE_80") {
                        LOGW("DebugRamGovernor [>80%%]: VmRSS=%zu/%zu MB | availRAM=%zu MB -> frenado 75ms/tok",
                             rss_mb, max_rss_budget, avail_mb);
                    }
                    gov_state = "THROTTLE_80";
                    throttle_delay_ms = 75;
                } else if (rss_mb >= soft_70_rss_mb || avail_mb < 320) {
                    if (is_embed_loaded_.load()) unload_embed_model();
                    if (gov_state != "THROTTLE_70") {
                        LOGI("DebugRamGovernor [70-80%%]: VmRSS=%zu/%zu MB | availRAM=%zu MB -> frenado 25ms/tok",
                             rss_mb, max_rss_budget, avail_mb);
                    }
                    gov_state = "THROTTLE_70";
                    throttle_delay_ms = 25;
                } else {
                    gov_state = "NORMAL";
                    throttle_delay_ms = 0;
                }
            }

            const bool over_rss_ceiling = strict_budget && (rss_mb >= max_rss_budget);
            if (avail_mb < min_safe_ram || (anon_mb > 0 && anon_mb > max_safe_anon_mb) || over_rss_ceiling) {
                if (is_embed_loaded_.load()) {
                    unload_embed_model();
                    avail_mb = get_available_ram_mb();
                    anon_mb = get_process_anon_rss_mb();
                    rss_mb = get_process_rss_mb();
                }
                const bool still_over_rss = strict_budget && (rss_mb >= max_rss_budget);
                if (avail_mb < min_safe_ram || (anon_mb > 0 && anon_mb > max_safe_anon_mb) || still_over_rss) {
                    LOGW("LMK Guard activado (RAM libre=%zu MB < %zu MB | RssAnon=%zu MB > %zu MB | VmRSS=%zu/%zu MB), deteniendo generación segura",
                         avail_mb, min_safe_ram, anon_mb, max_safe_anon_mb, rss_mb, max_rss_budget);
                    if (debug_governor) gov_state = "LIMIT_HALT";
                    low_ram_triggered = true;
                    emit_utf8_safe("", true);
                    break;
                }
            }
        }

        // Context Shifting automático si la ventana KV alcanza el borde
        if (n_cur >= max_context - 4) {
            if (mem && llama_memory_can_shift(mem)) {
                const llama_pos n_keep = std::min<llama_pos>(
                    static_cast<llama_pos>(std::max<size_t>(16, system_prefix_tokens)),
                    n_cur / 2
                );
                const llama_pos n_discard = std::max<llama_pos>(64, (n_cur - n_keep) / 2);
                if (n_discard > 0 && n_keep + n_discard < n_cur) {
                    llama_memory_seq_rm(mem, 0, n_keep, n_keep + n_discard);
                    llama_memory_seq_add(mem, 0, n_keep + n_discard, n_cur, -n_discard);
                    if (static_cast<size_t>(n_keep + n_discard) <= kv_cache_tokens_.size()) {
                        kv_cache_tokens_.erase(
                            kv_cache_tokens_.begin() + n_keep,
                            kv_cache_tokens_.begin() + n_keep + n_discard
                        );
                    }
                    n_cur -= n_discard;
                    LOGI("Context Shifting ejecutado: descartados %d tokens, n_cur=%d/%d",
                         static_cast<int>(n_discard), static_cast<int>(n_cur), max_context);
                } else {
                    break;
                }
            } else {
                LOGW("Límite de contexto alcanzado (%d) y arquitectura sin shifting; finalizando turno.", max_context);
                break;
            }
        }

        llama_token new_token_id = llama_sampler_sample(smpl, lctx_, -1);
        llama_sampler_accept(smpl, new_token_id);

        if (llama_vocab_is_eog(vocab, new_token_id)) {
            break;
        }

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

        batch.n_tokens = 1;
        batch.token[0] = new_token_id;
        batch.pos[0] = n_cur;
        batch.n_seq_id[0] = 1;
        batch.seq_id[0][0] = 0;
        batch.logits[0] = 1;

        n_cur++;

        if (llama_decode(lctx_, batch) != 0) {
            LOGE("llama_decode falló durante la generación");
            llama_memory_clear(mem, true);
            kv_cache_tokens_.clear();
            break;
        }

        kv_cache_tokens_.push_back(new_token_id);

        const uint32_t effective_throttle_ms = std::max<uint32_t>(
            debug_governor ? static_cast<uint32_t>(throttle_delay_ms) : 0u,
            thermal_throttle_ms_.load()
        );
        if (effective_throttle_ms > 0) {
            std::this_thread::sleep_for(std::chrono::milliseconds(effective_throttle_ms));
        }
    }

    emit_utf8_safe("", true);

    llama_batch_free(batch);
    llama_sampler_free(smpl);

    if (low_ram_triggered && mem) {
        llama_memory_clear(mem, true);
        kv_cache_tokens_.clear();
    }

    auto end_time = std::chrono::steady_clock::now();
    double elapsed = std::chrono::duration<double>(end_time - start_time).count();
    double decode_elapsed = std::chrono::duration<double>(end_time - decode_start_time).count();
    double decode_tps = (decode_elapsed > 0.0 && token_count > 0) ? (token_count / decode_elapsed) : 0.0;
    const size_t rss_mb = get_process_rss_mb();
    const size_t anon_mb = get_process_anon_rss_mb();
    const size_t avail_mb = get_available_ram_mb();

    {
        std::lock_guard<std::mutex> s_lock(stats_mutex_);
        last_stats_.tokens_generated = token_count;
        last_stats_.elapsed_seconds = elapsed;
        last_stats_.tokens_per_second = (elapsed > 0.0) ? (token_count / elapsed) : 0.0;
        last_stats_.decode_tokens_per_second = decode_tps;
        last_stats_.prefill_ms = prefill_ms;
        last_stats_.memory_rss_mb = rss_mb;
        last_stats_.anon_rss_mb = anon_mb;
        last_stats_.available_ram_mb = avail_mb;
        last_stats_.hardware_tier = tier_to_cstr(active_tier_);
        last_stats_.soc_model = soc_model_;
        last_stats_.backend_name = std::string("llama.cpp ") + tier_to_cstr(active_tier_);
        last_stats_.ram_governor_state = gov_state;
    }

    is_generating_.store(false);
    LOGI("Inferencia completada | tok=%u | prefill=%.0fms | elapsed=%.2fs | tps=%.2f | decode_tps=%.2f | gov=%s | rss=%zu MB | anon=%zu MB | availRAM=%zu MB",
         token_count, prefill_ms, elapsed, (elapsed > 0.0) ? (token_count / elapsed) : 0.0, decode_tps, gov_state.c_str(), rss_mb, anon_mb, avail_mb);
}

void KofuEngine::cancel_generation() {
    is_cancelled_.store(true);
}

double KofuEngine::probe_ufs_storage(const std::string& app_files_dir) {
    return moe_pager_.probe_storage_bandwidth_mbps(app_files_dir);
}

void KofuEngine::configure_moe_pager(size_t warm_budget_mb, size_t total_ram_mb, bool force_ufs_mode) {
    moe_pager_.configure(warm_budget_mb, total_ram_mb, force_ufs_mode);
}

uint32_t KofuEngine::update_thermal_status(int android_thermal_status) {
    thermal_status_.store(android_thermal_status);
    const size_t rss_mb = get_process_rss_mb();
    const size_t max_rss = max_rss_budget_mb_.load();
    const uint32_t delay = moe_pager_.apply_thermal_and_ram_guard(android_thermal_status, rss_mb, max_rss);
    thermal_throttle_ms_.store(delay);
    return delay;
}

std::string KofuEngine::get_moe_pager_stats_json() const {
    return moe_pager_.get_stats_json();
}

std::string KofuEngine::get_stats_json() const {
    std::lock_guard<std::mutex> lock(stats_mutex_);
    std::ostringstream ss;
    ss << "{"
       << "\"tokens_generated\":" << last_stats_.tokens_generated << ","
       << "\"elapsed_seconds\":" << last_stats_.elapsed_seconds << ","
       << "\"tokens_per_sec\":" << last_stats_.tokens_per_second << ","
       << "\"decode_tokens_per_sec\":" << last_stats_.decode_tokens_per_second << ","
       << "\"prefill_ms\":" << last_stats_.prefill_ms << ","
       << "\"load_ms\":" << last_stats_.load_ms << ","
       << "\"memory_rss_mb\":" << last_stats_.memory_rss_mb << ","
       << "\"anon_rss_mb\":" << last_stats_.anon_rss_mb << ","
       << "\"available_ram_mb\":" << (last_stats_.available_ram_mb > 0 ? last_stats_.available_ram_mb : get_available_ram_mb()) << ","
       << "\"n_ctx\":" << last_stats_.active_n_ctx << ","
       << "\"hardware_tier\":\"" << last_stats_.hardware_tier << "\","
       << "\"ram_governor_state\":\"" << last_stats_.ram_governor_state << "\","
       << "\"silicon_tier\":\"" << KOFU_SILICON_TIER_STR << "\","
       << "\"isa_arch\":\"" << KOFU_ARM_ARCH_STR << "\","
       << "\"soc_model\":\"" << last_stats_.soc_model << "\","
       << "\"backend\":\"" << last_stats_.backend_name << "\","
       << "\"moe_pager\":" << moe_pager_.get_stats_json()
       << "}";
    return ss.str();
}

} // namespace kofu
