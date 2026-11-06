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

    int fd = open(filepath.c_str(), O_RDONLY);
    if (fd < 0) {
        LOGE("Error abriendo archivo %s", filepath.c_str());
        return false;
    }

    struct stat sb{};
    if (fstat(fd, &sb) < 0) {
        LOGE("Error obteniendo tamaño de archivo %s", filepath.c_str());
        close(fd);
        return false;
    }

    size_t file_size = static_cast<size_t>(sb.st_size);
    if (file_size < 16) {
        LOGE("Archivo demasiado pequeño (%zu bytes)", file_size);
        close(fd);
        return false;
    }

    metadata_.file_size = file_size;
    size_t last_slash = filepath.find_last_of('/');
    metadata_.filename = (last_slash != std::string::npos) ? filepath.substr(last_slash + 1) : filepath;

    uint8_t header_buf[64] = {0};
    ssize_t bytes_read = pread(fd, header_buf, sizeof(header_buf), 0);
    close(fd);

    if (bytes_read < 8) {
        LOGE("Error leyendo cabecera del archivo %s", filepath.c_str());
        return false;
    }

    if (!parse_metadata(filepath, header_buf, static_cast<size_t>(bytes_read))) {
        LOGW("Archivo verificado pero metadatos no reconocidos con precisión");
    }

    LOGI("Cabecera de modelo validada exitosamente: %s (%zu MB)",
         metadata_.filename.c_str(), metadata_.file_size / (1024 * 1024));
    return metadata_.is_valid;
}

bool ModelLoader::load_fd(int fd, size_t size, const std::string& name) {
    unload();

    if (fd < 0 || size < 16) {
        LOGE("Descriptor de archivo inválido o tamaño insuficiente (fd=%d, size=%zu)", fd, size);
        return false;
    }

    metadata_.file_size = size;
    metadata_.filename = name;

    uint8_t header_buf[64] = {0};
    ssize_t bytes_read = pread(fd, header_buf, sizeof(header_buf), 0);
    if (bytes_read < 8) {
        LOGE("Error leyendo cabecera para descriptor fd=%d", fd);
        return false;
    }

    if (!parse_metadata(name, header_buf, static_cast<size_t>(bytes_read))) {
        LOGW("FD verificado pero metadatos no reconocidos con precisión");
    }

    LOGI("Cabecera de modelo validada vía FD: %s (%zu MB)",
         metadata_.filename.c_str(), metadata_.file_size / (1024 * 1024));
    return metadata_.is_valid;
}

void ModelLoader::unload() {
    metadata_ = ModelMetadata{};
}

bool ModelLoader::parse_metadata(const std::string& filepath, const uint8_t* header, size_t header_size) {
    if (!header || header_size < 4) return false;

    if (check_gguf_magic(header, header_size, metadata_)) {
        return true;
    }
    if (check_litert_magic(header, header_size, metadata_)) {
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
        // Version uint32 (lectura segura con memcpy para evitar fallos de alineación en ARM)
        uint32_t version = 0;
        std::memcpy(&version, data + 4, sizeof(version));
        out.version = version;
        if (size >= 24) {
            uint64_t tensor_cnt = 0;
            uint64_t kv_cnt = 0;
            std::memcpy(&tensor_cnt, data + 8, sizeof(tensor_cnt));
            std::memcpy(&kv_cnt, data + 16, sizeof(kv_cnt));
            out.tensor_count = tensor_cnt;
            out.kv_count = kv_cnt;
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
