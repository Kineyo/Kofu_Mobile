#pragma once

#include <string>
#include <string_view>
#include "model_loader.hpp"

namespace kofu {

class PromptFormatter {
public:
    static std::string sanitize_input(std::string_view raw_prompt);
    static std::string format_chat_prompt(
        std::string_view user_prompt,
        std::string_view system_prompt = "Eres Kofu, un asistente de IA local útil, atento, privado y respetuoso. Responde de forma clara, precisa y concisa.",
        ModelFormat format = ModelFormat::LITERT
    );
};

} // namespace kofu
