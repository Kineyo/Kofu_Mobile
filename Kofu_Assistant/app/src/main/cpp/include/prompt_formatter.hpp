#pragma once

#include <string>
#include <string_view>
#include <vector>
#include "model_loader.hpp"

namespace kofu {

enum class PromptStyle {
    AUTO = 0,
    GEMMA = 1,
    CHATML = 2,
    LLAMA3 = 3,
    GEMMA_4 = 4
};

struct ChatTurn {
    std::string role;    // "system", "user", "model" / "assistant"
    std::string content;
};

class PromptFormatter {
public:
    static std::string sanitize_input(std::string_view raw_prompt);

    static std::string format_chat_prompt(
        std::string_view user_prompt,
        std::string_view system_prompt = "Eres Kofu, un asistente de IA local útil, atento, privado y respetuoso. Responde de forma clara, precisa y concisa.",
        PromptStyle style = PromptStyle::GEMMA
    );

    static std::string format_multi_turn_prompt(
        const std::vector<ChatTurn>& turns,
        std::string_view default_system = "Eres Kofu, un asistente de IA local útil, atento, privado y respetuoso. Responde de forma clara, precisa y concisa.",
        PromptStyle style = PromptStyle::GEMMA
    );
};

} // namespace kofu
