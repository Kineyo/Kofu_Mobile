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

void ModelLoader::unload() {
    if (mapped_data_ != nullptr && mapped_size_ > 0) {
        munmap(mapped_data_, mapped_size_);
        mapped_data_ = nullptr;
    }
    if (file_descriptor_ >= 0) {
        close(file_descriptor_);
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
    if (lower.ends_with(".litertlm") || lower.ends_with(".tflite") ||
        lower.ends_with(".task") || lower.ends_with(".bin")) {
        metadata_.format = ModelFormat::LITERT;
        metadata_.is_valid = true;
        return true;
    }

    metadata_.format = ModelFormat::UNKNOWN;
    metadata_.is_valid = false;
    return false;
}

bool ModelLoader::check_gguf_magic(const uint8_t* data, size_t size, ModelMetadata& out) {
    if (size < 4) return false;
    if (data[0] == 'G' && data[1] == 'G' && data[2] == 'U' && data[3] == 'F') {
        out.format = ModelFormat::GGUF;
        out.is_valid = true;
        if (size >= 8) {
            uint32_t version = 0;
            std::memcpy(&version, data + 4, sizeof(version));
            out.version = version;
        }
        return true;
    }
    return false;
}

bool ModelLoader::check_litert_magic(const uint8_t* data, size_t size, ModelMetadata& out) {
    if (size < 4) return false;

    // Check for FlatBuffers / TFL3 magic
    if (data[0] == 'T' && data[1] == 'F' && data[2] == 'L' && data[3] == '3') {
        out.format = ModelFormat::LITERT;
        out.is_valid = true;
        return true;
    }
    if (size >= 8 && data[4] == 'T' && data[5] == 'F' && data[6] == 'L' && data[7] == '3') {
        out.format = ModelFormat::LITERT;
        out.is_valid = true;
        return true;
    }
    // Check for Zip container (.task / .litertlm format packaging)
    if (data[0] == 0x50 && data[1] == 0x4B) { // PK
        out.format = ModelFormat::LITERT;
        out.is_valid = true;
        return true;
    }
    return false;
}

} // namespace kofu
