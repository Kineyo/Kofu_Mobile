#pragma once

#include <atomic>
#include <cstddef>
#include <cstdint>
#include <list>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

namespace kofu {

enum class StorageTier {
    UNKNOWN = 0,
    EMMC_SLOW = 1,   // < 600 MB/s -> usar sub-modelo podado residente en RAM
    UFS_FAST = 2     // >= 600 MB/s -> streaming directo de expertos Cold desde UFS
};

enum class MoePagerMode {
    DISABLED = 0,
    RAM_RESIDENT_PRUNED = 1, // Aura-Kaze-Go (5-7 expertos) 100% en RAM/mmap
    UFS_HIERARCHICAL = 2     // Kaze-4.7B (Hot RAM + Warm LRU + Cold UFS pread)
};

struct ExpertBlockKey {
    uint16_t layer_idx{0};
    uint16_t expert_idx{0};

    bool operator==(const ExpertBlockKey& other) const noexcept {
        return layer_idx == other.layer_idx && expert_idx == other.expert_idx;
    }
};

struct ExpertBlockKeyHash {
    size_t operator()(const ExpertBlockKey& k) const noexcept {
        return (static_cast<size_t>(k.layer_idx) << 16) | static_cast<size_t>(k.expert_idx);
    }
};

struct ExpertDiskSlice {
    uint64_t file_offset{0};
    size_t byte_size{0};
    bool is_hot_pinned{false};
};

struct MoePagerStats {
    double storage_bandwidth_mbps{0.0};
    std::string storage_tier{"UNKNOWN"};
    std::string pager_mode{"DISABLED"};
    size_t hot_pinned_mb{0};
    size_t warm_budget_mb{0};
    size_t warm_used_mb{0};
    uint64_t cache_hits{0};
    uint64_t cache_misses{0};
    double hit_rate_pct{0.0};
    uint64_t cold_bytes_read{0};
    int thermal_status{0};
    std::string thermal_guard_state{"NOMINAL"};
};

/**
 * Motor jerárquico de paginación MoE sobre almacenamiento UFS (100% MIT, C++20 puro).
 * Inspirado en el patrón Hot / Warm / Cold para ejecutar modelos Sparse MoE (ej. Kaze-4.7B-A530M)
 * manteniendo solo el núcleo activo (~240 MB Hot + caché LRU Warm acotada) en RAM física
 * y leyendo expertos inactivos (Cold) directamente desde almacenamiento flash UFS sin usar ZRAM.
 */
class MoeUfsPager {
public:
    static constexpr double kUfsFastThresholdMbps = 600.0;
    static constexpr size_t kPageAlignmentBytes = 16384; // Alineación 16 KB (Android 15+)

    MoeUfsPager();
    ~MoeUfsPager();

    MoeUfsPager(const MoeUfsPager&) = delete;
    MoeUfsPager& operator=(const MoeUfsPager&) = delete;

    // Micro-benchmark rápido (~64-100 ms) para clasificar almacenamiento interno UFS vs eMMC
    double probe_storage_bandwidth_mbps(const std::string& app_files_dir);

    // Configura presupuesto Warm LRU y selecciona automáticamente UFS_HIERARCHICAL vs RAM_RESIDENT_PRUNED
    void configure(size_t warm_budget_mb, size_t total_ram_mb, bool force_ufs_mode = false);

    // Adjunta el archivo de pesos en almacenamiento interno privado (filesDir) para lecturas Cold
    bool attach_model_file(const std::string& filepath, size_t hot_pinned_mb_estimate = 240);
    void detach();

    // Registra un slice de experto en disco (offset y tamaño en el archivo GGUF/Kaze)
    void register_expert_slice(uint16_t layer_idx, uint16_t expert_idx, uint64_t offset, size_t size, bool hot_pinned = false);

    // Obtiene los bytes de un experto:
    // 1) Hit en Warm LRU (O(1)) -> cero I/O
    // 2) Miss -> pread() directo desde UFS + posix_fadvise(DONTNEED) para no inflar Page Cache/VmRSS
    bool fetch_expert_bytes(uint16_t layer_idx, uint16_t expert_idx, std::vector<uint8_t>& out_buffer);

    // Gobernador térmico y de RAM (Anti-Chernobyl): encoge la caché Warm y aplica enfriamiento si sube la temperatura o VmRSS
    uint32_t apply_thermal_and_ram_guard(int android_thermal_status, size_t current_rss_mb, size_t max_rss_mb);

    // Libera memoria Warm ante onTrimMemory() de Android
    void trim_warm_cache(double retain_fraction);

    MoePagerStats get_stats() const;
    std::string get_stats_json() const;
    StorageTier get_storage_tier() const { return storage_tier_.load(); }
    MoePagerMode get_mode() const { return mode_.load(); }

private:
    struct CachedExpert {
        ExpertBlockKey key;
        std::vector<uint8_t> data;
    };

    void evict_until_budget_locked(size_t target_bytes);
    void advise_cold_pages_dontneed(uint64_t offset, size_t size) const;
    static std::string storage_tier_to_string(StorageTier tier);
    static std::string mode_to_string(MoePagerMode mode);

    mutable std::mutex mutex_;
    int model_fd_{-1};
    std::string model_path_;

    std::atomic<double> measured_mbps_{0.0};
    std::atomic<StorageTier> storage_tier_{StorageTier::UNKNOWN};
    std::atomic<MoePagerMode> mode_{MoePagerMode::DISABLED};

    size_t hot_pinned_mb_{0};
    size_t base_warm_budget_bytes_{512ULL * 1024ULL * 1024ULL};
    size_t active_warm_budget_bytes_{512ULL * 1024ULL * 1024ULL};
    size_t warm_used_bytes_{0};

    std::unordered_map<ExpertBlockKey, ExpertDiskSlice, ExpertBlockKeyHash> slice_table_;
    std::list<CachedExpert> lru_list_;
    std::unordered_map<ExpertBlockKey, std::list<CachedExpert>::iterator, ExpertBlockKeyHash> lru_index_;

    uint64_t cache_hits_{0};
    uint64_t cache_misses_{0};
    uint64_t cold_bytes_read_{0};
    int thermal_status_{0};
    std::string thermal_guard_state_{"NOMINAL"};
};

} // namespace kofu
