#include "model_loader.hpp"

#include <android/log.h>
#include <fcntl.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cstring>
#include <algorithm>

#define TAG "KofuModelLoader"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

namespace kofu {

ModelLoader::ModelLoader() = default;

ModelLoader::~ModelLoader() {
    unload();
}

bool ModelLoader::load(const std::string& filepath) {
    unload();

    file_descriptor_ = open(filepath.c_str(), O_RDONLY);
    if (file_descriptor_ < 0) {
        LOGE("Error abriendo archivo %s", filepath.c_str());
        return false;
    }
    owns_fd_ = true;

    struct stat sb{};
    if (fstat(file_descriptor_, &sb) < 0) {
        LOGE("Error obteniendo tamaño de archivo %s", filepath.c_str());
        close(file_descriptor_);
        file_descriptor_ = -1;
        return false;
    }

    mapped_size_ = static_cast<size_t>(sb.st_size);
    if (mapped_size_ < 16) {
        LOGE("Archivo demasiado pequeño (%zu bytes)", mapped_size_);
        close(file_descriptor_);
        file_descriptor_ = -1;
        return false;
    }

    void* addr = mmap(nullptr, mapped_size_, PROT_READ, MAP_SHARED, file_descriptor_, 0);
    if (addr == MAP_FAILED) {
        LOGE("mmap falló para %s (tamaño: %zu bytes)", filepath.c_str(), mapped_size_);
        close(file_descriptor_);
        file_descriptor_ = -1;
        mapped_size_ = 0;
        return false;
    }

    mapped_data_ = static_cast<uint8_t*>(addr);
    metadata_.file_size = mapped_size_;

    size_t last_slash = filepath.find_last_of('/');
    metadata_.filename = (last_slash != std::string::npos) ? filepath.substr(last_slash + 1) : filepath;

    if (!parse_metadata(filepath)) {
        LOGW("Archivo cargado pero metadatos no reconocidos con precisión");
    }

    LOGI("Modelo cargado exitosamente vía mmap: %s (%zu MB)",
         metadata_.filename.c_str(), mapped_size_ / (1024 * 1024));
    return true;
}

bool ModelLoader::load_fd(int fd, size_t size, const std::string& name) {
    unload();

    if (fd < 0 || size < 16) {
        LOGE("Descriptor de archivo inválido o tamaño insuficiente (fd=%d, size=%zu)", fd, size);
        return false;
    }

    file_descriptor_ = fd;
    owns_fd_ = false; // El FD proviene de ParcelFileDescriptor de Android
    mapped_size_ = size;

    void* addr = mmap(nullptr, mapped_size_, PROT_READ, MAP_SHARED, file_descriptor_, 0);
    if (addr == MAP_FAILED) {
        LOGE("mmap falló para descriptor fd=%d (tamaño: %zu bytes)", fd, mapped_size_);
        file_descriptor_ = -1;
        mapped_size_ = 0;
        return false;
    }

    mapped_data_ = static_cast<uint8_t*>(addr);
    metadata_.file_size = mapped_size_;
    metadata_.filename = name;

    if (!parse_metadata(name)) {
        LOGW("FD cargado pero metadatos no reconocidos con precisión");
    }

    LOGI("Modelo cargado exitosamente vía mmap (Zero-Copy FD): %s (%zu MB)",
         metadata_.filename.c_str(), mapped_size_ / (1024 * 1024));
    return true;
}

void ModelLoader::unload() {
    if (mapped_data_ != nullptr && mapped_size_ > 0) {
        munmap(mapped_data_, mapped_size_);
        mapped_data_ = nullptr;
    }
    if (file_descriptor_ >= 0) {
        if (owns_fd_) {
            close(file_descriptor_);
        }
        file_descriptor_ = -1;
    }
    mapped_size_ = 0;
    metadata_ = ModelMetadata{};
}

bool ModelLoader::parse_metadata(const std::string& filepath) {
    if (!mapped_data_ || mapped_size_ < 4) return false;

    if (check_gguf_magic(mapped_data_, mapped_size_, metadata_)) {
        return true;
    }
    if (check_litert_magic(mapped_data_, mapped_size_, metadata_)) {
        return true;
    }

    std::string lower = filepath;
    std::transform(lower.begin(), lower.end(), lower.begin(), ::tolower);
    if (lower.ends_with(".gguf")) {
        metadata_.format = ModelFormat::GGUF;
        metadata_.is_valid = true;
        return true;
    }
    if (lower.ends_with(".litertlm") || lower.ends_with(".tflite") || lower.ends_with(".task")) {
        metadata_.format = ModelFormat::LITERT;
        metadata_.is_valid = true;
        return true;
    }

    return false;
}

bool ModelLoader::check_gguf_magic(const uint8_t* data, size_t size, ModelMetadata& out) {
    if (size < 8) return false;
    // Magic: GGUF = 0x46554747 (GGUF en little endian)
    if (data[0] == 'G' && data[1] == 'G' && data[2] == 'U' && data[3] == 'F') {
        out.format = ModelFormat::GGUF;
        out.is_valid = true;
        // Version uint32
        out.version = *reinterpret_cast<const uint32_t*>(data + 4);
        if (size >= 24) {
            out.tensor_count = *reinterpret_cast<const uint64_t*>(data + 8);
            out.kv_count = *reinterpret_cast<const uint64_t*>(data + 16);
        }
        LOGI("Cabecera GGUF detectada: versión %u, tensores %lu, kv %lu",
             out.version, (unsigned long)out.tensor_count, (unsigned long)out.kv_count);
        return true;
    }
    return false;
}

bool ModelLoader::check_litert_magic(const uint8_t* data, size_t size, ModelMetadata& out) {
    if (size < 8) return false;
    // FlatBuffers / LiteRT: offset 4 contiene 'TFL3'
    if (data[4] == 'T' && data[5] == 'F' && data[6] == 'L' && data[7] == '3') {
        out.format = ModelFormat::LITERT;
        out.is_valid = true;
        out.version = 3;
        LOGI("Cabecera LiteRT FlatBuffers detectada (TFL3)");
        return true;
    }
    return false;
}

} // namespace kofu
