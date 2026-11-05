#include "prompt_formatter.hpp"

#include <regex>
#include <sstream>

namespace kofu {

std::string PromptFormatter::sanitize_input(std::string_view raw_prompt) {
    static const std::regex control_tokens_regex(
        R"(<\|(?:begin_of_text|end_of_text|start_header_id|end_header_id|eot_id|im_start|im_end|system|user|assistant)\|>|<(?:start_of_turn|end_of_turn)>)",
        std::regex_constants::icase
    );

    std::string text(raw_prompt);
    text = std::regex_replace(text, control_tokens_regex, "");

    // Trim leading whitespace
    size_t first = text.find_first_not_of(" \t\n\r");
    if (first == std::string::npos) return "";

    // Trim trailing whitespace
    size_t last = text.find_last_not_of(" \t\n\r");
    return text.substr(first, (last - first + 1));
}

std::string PromptFormatter::format_chat_prompt(
    std::string_view user_prompt,
    std::string_view system_prompt,
    ModelFormat format
) {
    std::string clean_user = sanitize_input(user_prompt);
    std::ostringstream ss;

    if (format == ModelFormat::LITERT) {
        // Gemma standard instruction format
        ss << "<start_of_turn>user\n"
           << system_prompt << "\n\n"
           << clean_user << "<end_of_turn>\n"
           << "<start_of_turn>model\n";
    } else {
        // Llama-3 / ChatML format
        ss << "<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n"
           << system_prompt << "<|eot_id|>\n"
           << "<|start_header_id|>user<|end_header_id|>\n"
           << clean_user << "<|eot_id|>\n"
           << "<|start_header_id|>assistant<|end_header_id|>\n";
    }

    return ss.str();
}

} // namespace kofu
