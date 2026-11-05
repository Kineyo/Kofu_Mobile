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
    PromptStyle style
) {
    std::vector<ChatTurn> turns;
    if (!system_prompt.empty()) {
        turns.push_back({"system", std::string(system_prompt)});
    }
    turns.push_back({"user", std::string(user_prompt)});
    return format_multi_turn_prompt(turns, system_prompt, style);
}

std::string PromptFormatter::format_multi_turn_prompt(
    const std::vector<ChatTurn>& turns,
    std::string_view default_system,
    PromptStyle style
) {
    std::ostringstream ss;
    std::string system_msg(default_system);

    // Look for explicit system message in turns if present
    for (const auto& t : turns) {
        if (t.role == "system") {
            system_msg = sanitize_input(t.content);
            break;
        }
    }

    if (style == PromptStyle::GEMMA || style == PromptStyle::AUTO) {
        // Gemma 1 / Gemma 2 official Turn template:
        // System instruction is prepended to the first user turn
        bool first_user = true;
        for (const auto& t : turns) {
            if (t.role == "system") continue;

            std::string clean = sanitize_input(t.content);
            if (clean.empty()) continue;

            if (t.role == "user") {
                ss << "<start_of_turn>user\n";
                if (first_user && !system_msg.empty()) {
                    ss << system_msg << "\n\n";
                    first_user = false;
                }
                ss << clean << "<end_of_turn>\n";
            } else if (t.role == "model" || t.role == "assistant") {
                ss << "<start_of_turn>model\n" << clean << "<end_of_turn>\n";
            }
        }
        // Open turn for model generation
        ss << "<start_of_turn>model\n";
    } else if (style == PromptStyle::CHATML) {
        if (!system_msg.empty()) {
            ss << "<|im_start|>system\n" << system_msg << "<|im_end|>\n";
        }
        for (const auto& t : turns) {
            if (t.role == "system") continue;
            std::string clean = sanitize_input(t.content);
            if (clean.empty()) continue;
            std::string role = (t.role == "model") ? "assistant" : t.role;
            ss << "<|im_start|>" << role << "\n" << clean << "<|im_end|>\n";
        }
        ss << "<|im_start|>assistant\n";
    } else { // LLAMA3
        ss << "<|begin_of_text|>";
        if (!system_msg.empty()) {
            ss << "<|start_header_id|>system<|end_header_id|>\n\n" << system_msg << "<|eot_id|>";
        }
        for (const auto& t : turns) {
            if (t.role == "system") continue;
            std::string clean = sanitize_input(t.content);
            if (clean.empty()) continue;
            std::string role = (t.role == "model") ? "assistant" : t.role;
            ss << "<|start_header_id|>" << role << "<|end_header_id|>\n\n" << clean << "<|eot_id|>";
        }
        ss << "<|start_header_id|>assistant<|end_header_id|>\n\n";
    }

    return ss.str();
}

} // namespace kofu
