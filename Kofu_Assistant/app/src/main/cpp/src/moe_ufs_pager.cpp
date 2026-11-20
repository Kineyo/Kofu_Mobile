#include "moe_ufs_pager.hpp"

#include <algorithm>
#include <chrono>
#include <cstring>
#include <fcntl.h>
#include <sstream>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>

#ifdef __ANDROID__
#include <android/log.h>
#define PAGER_LOGI(...) __android_log_print(ANDROID_LOG_INFO, "KofuMoePager", __VA_ARGS__)
#define PAGER_LOGW(...) __android_log_print(ANDROID_LOG_WARN, "KofuMoePager", __VA_ARGS__)
#else
#define PAGER_LOGI(...) ((void)0)
#define PAGER_LOGW(...) ((void)0)
#endif

namespace kofu {

MoeUfsPager::MoeUfsPager() = default;

MoeUfsPager::~MoeUfsPager() {
    detach();
}

double MoeUfsPager::probe_storage_bandwidth_mbps(const std::string& app_files_dir) {
    if (app_files_dir.empty()) {
        return measured_mbps_.load();
    }

    const std::string probe_path = app_files_dir + "/.kaze_ufs_probe.tmp";
    constexpr size_t kProbeBytes = 16ULL * 1024ULL * 1024ULL; // 16 MB alineado
    constexpr size_t kChunkBytes = 256ULL * 1024ULL;          // Bloques de 256 KB

    std::vector<uint8_t> buf(kChunkBytes, 0xA5);

    int wfd = ::open(probe_path.c_str(), O_CREAT | O_WRONLY | O_TRUNC, 0600);
    if (wfd < 0) {
        PAGER_LOGW("No se pudo crear archivo de sonda UFS en %s", probe_path.c_str());
        return 0.0;
    }

    size_t written = 0;
    while (written < kProbeBytes) {
        ssize_t n = ::write(wfd, buf.data(), buf.size());
        if (n <= 0) break;
        written += static_cast<size_t>(n);
    }
    ::fsync(wfd);
#if defined(POSIX_FADV_DONTNEED)
    ::posix_fadvise(wfd, 0, static_cast<off_t>(written), POSIX_FADV_DONTNEED);
#endif
    ::close(wfd);

    int rfd = ::open(probe_path.c_str(), O_RDONLY);
    if (rfd < 0) {
        ::unlink(probe_path.c_str());
        return 0.0;
    }

#if defined(POSIX_FADV_DONTNEED)
    ::posix_fadvise(rfd, 0, static_cast<off_t>(written), POSIX_FADV_DONTNEED);
#endif

    const auto t0 = std::chrono::steady_clock::now();
    size_t total_read = 0;
    while (total_read < written) {
        ssize_t n = ::pread(rfd, buf.data(), buf.size(), static_cast<off_t>(total_read));
        if (n <= 0) break;
        total_read += static_cast<size_t>(n);
    }
    const auto t1 = std::chrono::steady_clock::now();

    ::close(rfd);
    ::unlink(probe_path.c_str());

    const double secs = std::chrono::duration<double>(t1 - t0).count();
    const double mbps = (secs > 1e-6 && total_read > 0)
        ? (static_cast<double>(total_read) / (1024.0 * 1024.0)) / secs
        : 0.0;

    measured_mbps_.store(mbps);
    const StorageTier tier = (mbps >= kUfsFastThresholdMbps)
        ? StorageTier::UFS_FAST
        : StorageTier::EMMC_SLOW;
    storage_tier_.store(tier);

    PAGER_LOGI("Sonda UFS completada: %.1f MB/s -> tier=%s", mbps, storage_tier_to_string(tier).c_str());
    return mbps;
}

void MoeUfsPager::configure(size_t warm_budget_mb, size_t total_ram_mb, bool force_ufs_mode) {
    std::lock_guard<std::mutex> lock(mutex_);

    const size_t clamped_mb = std::clamp<size_t>(warm_budget_mb, 128, 1024);
    base_warm_budget_bytes_ = clamped_mb * 1024ULL * 1024ULL;
    active_warm_budget_bytes_ = base_warm_budget_bytes_;

    const bool fast_ufs = (storage_tier_.load() == StorageTier::UFS_FAST) || force_ufs_mode;
    const bool enough_ram_for_hierarchical = (total_ram_mb == 0 || total_ram_mb > 3400);

    if (fast_ufs && enough_ram_for_hierarchical) {
        mode_.store(MoePagerMode::UFS_HIERARCHICAL);
    } else {
        mode_.store(MoePagerMode::RAM_RESIDENT_PRUNED);
    }

    evict_until_budget_locked(active_warm_budget_bytes_);
    PAGER_LOGI(
        "MoeUfsPager configurado: mode=%s | warm_budget=%zu MB | total_ram=%zu MB",
        mode_to_string(mode_.load()).c_str(),
        clamped_mb,
        total_ram_mb
    );
}

bool MoeUfsPager::attach_model_file(const std::string& filepath, size_t hot_pinned_mb_estimate) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (model_fd_ >= 0) {
        ::close(model_fd_);
        model_fd_ = -1;
    }

    int fd = ::open(filepath.c_str(), O_RDONLY);
    if (fd < 0) {
        PAGER_LOGW("No se pudo abrir archivo de pesos para paginación UFS: %s", filepath.c_str());
        return false;
    }

#if defined(POSIX_FADV_RANDOM)
    // Evita read-ahead innecesario del kernel al saltar entre expertos MoE en UFS
    ::posix_fadvise(fd, 0, 0, POSIX_FADV_RANDOM);
#endif

    model_fd_ = fd;
    model_path_ = filepath;
    hot_pinned_mb_ = hot_pinned_mb_estimate;
    lru_list_.clear();
    lru_index_.clear();
    warm_used_bytes_ = 0;
    cache_hits_ = 0;
    cache_misses_ = 0;
    cold_bytes_read_ = 0;

    PAGER_LOGI("Archivo MoE adjuntado para streaming UFS: %s (Hot~%zu MB)", filepath.c_str(), hot_pinned_mb_);
    return true;
}

void MoeUfsPager::detach() {
    std::lock_guard<std::mutex> lock(mutex_);
    if (model_fd_ >= 0) {
#if defined(POSIX_FADV_DONTNEED)
        ::posix_fadvise(model_fd_, 0, 0, POSIX_FADV_DONTNEED);
#endif
        ::close(model_fd_);
        model_fd_ = -1;
    }
    model_path_.clear();
    slice_table_.clear();
    lru_list_.clear();
    lru_index_.clear();
    warm_used_bytes_ = 0;
}

void MoeUfsPager::register_expert_slice(
    uint16_t layer_idx,
    uint16_t expert_idx,
    uint64_t offset,
    size_t size,
    bool hot_pinned
) {
    std::lock_guard<std::mutex> lock(mutex_);
    slice_table_[ExpertBlockKey{layer_idx, expert_idx}] = ExpertDiskSlice{offset, size, hot_pinned};
}

bool MoeUfsPager::fetch_expert_bytes(
    uint16_t layer_idx,
    uint16_t expert_idx,
    std::vector<uint8_t>& out_buffer
) {
    std::lock_guard<std::mutex> lock(mutex_);
    const ExpertBlockKey key{layer_idx, expert_idx};

    // 1) Fast path: Hit en caché Warm LRU
    auto it = lru_index_.find(key);
    if (it != lru_index_.end()) {
        lru_list_.splice(lru_list_.begin(), lru_list_, it->second);
        out_buffer = it->second->data;
        ++cache_hits_;
        return true;
    }

    // 2) Cold path: lectura directa desde almacenamiento interno UFS
    auto slice_it = slice_table_.find(key);
    if (model_fd_ < 0 || slice_it == slice_table_.end() || slice_it->second.byte_size == 0) {
        return false;
    }

    const ExpertDiskSlice slice = slice_it->second;
    out_buffer.resize(slice.byte_size);

    size_t total_read = 0;
    while (total_read < slice.byte_size) {
        ssize_t n = ::pread(
            model_fd_,
            out_buffer.data() + total_read,
            slice.byte_size - total_read,
            static_cast<off_t>(slice.file_offset + total_read)
        );
        if (n <= 0) {
            return false;
        }
        total_read += static_cast<size_t>(n);
    }

    // Evitar que el Page Cache de Android retenga páginas Cold y dispare lmkd
    advise_cold_pages_dontneed(slice.file_offset, slice.byte_size);

    ++cache_misses_;
    cold_bytes_read_ += total_read;

    // Insertar en Warm LRU respetando el presupuesto activo
    if (total_read <= active_warm_budget_bytes_) {
        evict_until_budget_locked(active_warm_budget_bytes_ - total_read);
        lru_list_.push_front(CachedExpert{key, out_buffer});
        lru_index_[key] = lru_list_.begin();
        warm_used_bytes_ += total_read;
    }
    return true;
}

uint32_t MoeUfsPager::apply_thermal_and_ram_guard(
    int android_thermal_status,
    size_t current_rss_mb,
    size_t max_rss_mb
) {
    std::lock_guard<std::mutex> lock(mutex_);
    thermal_status_ = android_thermal_status;

    const double rss_ratio = (max_rss_mb > 0)
        ? (static_cast<double>(current_rss_mb) / static_cast<double>(max_rss_mb))
        : 0.0;

    // Nivel crítico (THERMAL_STATUS_CRITICAL=4 / EMERGENCY=5 o RSS >= 92%):
    // Vaciar 75% de la caché Warm y aplicar pausa de enfriamiento (anti-reactor nuclear)
    if (android_thermal_status >= 4 || rss_ratio >= 0.92) {
        active_warm_budget_bytes_ = std::max<size_t>(64ULL * 1024ULL * 1024ULL, base_warm_budget_bytes_ / 4);
        evict_until_budget_locked(active_warm_budget_bytes_);
        thermal_guard_state_ = "CRITICAL_COOLING";
        return 60; // 60 ms throttle por token
    }

    // Nivel severo (THERMAL_STATUS_SEVERE=3 o RSS >= 82%): reducir Warm al 50%
    if (android_thermal_status >= 3 || rss_ratio >= 0.82) {
        active_warm_budget_bytes_ = std::max<size_t>(128ULL * 1024ULL * 1024ULL, base_warm_budget_bytes_ / 2);
        evict_until_budget_locked(active_warm_budget_bytes_);
        thermal_guard_state_ = "THERMAL_THROTTLE";
        return 25; // 25 ms throttle por token
    }

    // Nivel moderado (THERMAL_STATUS_MODERATE=2 o RSS >= 72%): reducir Warm al 75%
    if (android_thermal_status >= 2 || rss_ratio >= 0.72) {
        active_warm_budget_bytes_ = (base_warm_budget_bytes_ * 3) / 4;
        evict_until_budget_locked(active_warm_budget_bytes_);
        thermal_guard_state_ = "WARM_GUARD";
        return 8; // 8 ms suavizado
    }

    active_warm_budget_bytes_ = base_warm_budget_bytes_;
    thermal_guard_state_ = "NOMINAL";
    return 0;
}

void MoeUfsPager::trim_warm_cache(double retain_fraction) {
    std::lock_guard<std::mutex> lock(mutex_);
    const double frac = std::clamp(retain_fraction, 0.0, 1.0);
    const size_t target = static_cast<size_t>(static_cast<double>(active_warm_budget_bytes_) * frac);
    evict_until_budget_locked(target);
}

void MoeUfsPager::evict_until_budget_locked(size_t target_bytes) {
    while (warm_used_bytes_ > target_bytes && !lru_list_.empty()) {
        const auto& back = lru_list_.back();
        warm_used_bytes_ -= back.data.size();
        lru_index_.erase(back.key);
        lru_list_.pop_back();
    }
}

void MoeUfsPager::advise_cold_pages_dontneed(uint64_t offset, size_t size) const {
#if defined(POSIX_FADV_DONTNEED)
    if (model_fd_ >= 0 && size > 0) {
        const uint64_t aligned_off = (offset / kPageAlignmentBytes) * kPageAlignmentBytes;
        const size_t aligned_len = size + static_cast<size_t>(offset - aligned_off);
        ::posix_fadvise(model_fd_, static_cast<off_t>(aligned_off), static_cast<off_t>(aligned_len), POSIX_FADV_DONTNEED);
    }
#else
    (void)offset;
    (void)size;
#endif
}

std::string MoeUfsPager::storage_tier_to_string(StorageTier tier) {
    switch (tier) {
        case StorageTier::UFS_FAST: return "UFS_FAST";
        case StorageTier::EMMC_SLOW: return "EMMC_SLOW";
        default: return "UNKNOWN";
    }
}

std::string MoeUfsPager::mode_to_string(MoePagerMode mode) {
    switch (mode) {
        case MoePagerMode::UFS_HIERARCHICAL: return "UFS_HIERARCHICAL";
        case MoePagerMode::RAM_RESIDENT_PRUNED: return "RAM_RESIDENT_PRUNED";
        default: return "DISABLED";
    }
}

MoePagerStats MoeUfsPager::get_stats() const {
    std::lock_guard<std::mutex> lock(mutex_);
    MoePagerStats s;
    s.storage_bandwidth_mbps = measured_mbps_.load();
    s.storage_tier = storage_tier_to_string(storage_tier_.load());
    s.pager_mode = mode_to_string(mode_.load());
    s.hot_pinned_mb = hot_pinned_mb_;
    s.warm_budget_mb = active_warm_budget_bytes_ / (1024ULL * 1024ULL);
    s.warm_used_mb = warm_used_bytes_ / (1024ULL * 1024ULL);
    s.cache_hits = cache_hits_;
    s.cache_misses = cache_misses_;
    const uint64_t total = cache_hits_ + cache_misses_;
    s.hit_rate_pct = (total > 0) ? (100.0 * static_cast<double>(cache_hits_) / static_cast<double>(total)) : 0.0;
    s.cold_bytes_read = cold_bytes_read_;
    s.thermal_status = thermal_status_;
    s.thermal_guard_state = thermal_guard_state_;
    return s;
}

std::string MoeUfsPager::get_stats_json() const {
    const MoePagerStats s = get_stats();
    std::ostringstream ss;
    ss << "{"
       << "\"storage_mbps\":" << s.storage_bandwidth_mbps << ","
       << "\"storage_tier\":\"" << s.storage_tier << "\","
       << "\"pager_mode\":\"" << s.pager_mode << "\","
       << "\"hot_pinned_mb\":" << s.hot_pinned_mb << ","
       << "\"warm_budget_mb\":" << s.warm_budget_mb << ","
       << "\"warm_used_mb\":" << s.warm_used_mb << ","
       << "\"cache_hits\":" << s.cache_hits << ","
       << "\"cache_misses\":" << s.cache_misses << ","
       << "\"hit_rate_pct\":" << s.hit_rate_pct << ","
       << "\"cold_mb_read\":" << (static_cast<double>(s.cold_bytes_read) / (1024.0 * 1024.0)) << ","
       << "\"thermal_status\":" << s.thermal_status << ","
       << "\"thermal_guard_state\":\"" << s.thermal_guard_state << "\""
       << "}";
    return ss.str();
}

} // namespace kofu
