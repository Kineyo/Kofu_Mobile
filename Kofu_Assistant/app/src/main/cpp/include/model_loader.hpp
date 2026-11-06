#pragma once

#include <string>
#include <cstddef>
#include <cstdint>

namespace kofu {

enum class ModelFormat {
    UNKNOWN = 0,
    LITERT = 1,
    GGUF = 2
};

struct ModelMetadata {
    ModelFormat format = ModelFormat::UNKNOWN;
    std::string filename;
    size_t file_size = 0;
    uint32_t version = 0;
    uint64_t tensor_count = 0;
    uint64_t kv_count = 0;
    bool is_valid = false;
};

class ModelLoader {
public:
    ModelLoader();
    ~ModelLoader();

    bool load(const std::string& filepath);
    bool load_fd(int fd, size_t size, const std::string& name = "imported_fd_model");
    void unload();

    [[nodiscard]] bool is_loaded() const { return metadata_.is_valid; }
    [[nodiscard]] bool is_valid() const { return metadata_.is_valid; }
    [[nodiscard]] const ModelMetadata& get_metadata() const { return metadata_; }
    [[nodiscard]] size_t get_size() const { return metadata_.file_size; }

private:
    ModelMetadata metadata_;

    bool parse_metadata(const std::string& filepath, const uint8_t* header, size_t header_size);
    static bool check_gguf_magic(const uint8_t* data, size_t size, ModelMetadata& out);
    static bool check_litert_magic(const uint8_t* data, size_t size, ModelMetadata& out);
};

} // namespace kofu
