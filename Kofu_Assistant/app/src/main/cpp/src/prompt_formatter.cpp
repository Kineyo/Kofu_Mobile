#include "prompt_formatter.hpp"

#include <algorithm>
#include <array>
#include <cctype>
#include <sstream>

namespace kofu {

namespace {

bool starts_with_icase(std::string_view haystack, size_t pos, std::string_view needle) {
    if (pos + needle.size() > haystack.size()) return false;
    for (size_t i = 0; i < needle.size(); ++i) {
        const unsigned char a = static_cast<unsigned char>(haystack[pos + i]);
        const unsigned char b = static_cast<unsigned char>(needle[i]);
        if (std::tolower(a) != std::tolower(b)) return false;
    }
    return true;
}

std::string normalize_role(std::string_view role) {
    std::string lower;
    lower.reserve(role.size());
    for (unsigned char c : role) {
        lower.push_back(static_cast<char>(std::tolower(c)));
    }
    if (lower == "assistant" || lower == "model") return "model";
    if (lower == "system") return "system";
    return "user";
}

} // namespace

std::string PromptFormatter::sanitize_input(std::string_view raw_prompt) {
    static constexpr std::array<std::string_view, 17> kControlTokens = {
        "<|start_header_id|>",
        "<|end_header_id|>",
        "<|begin_of_text|>",
        "<|end_of_text|>",
        "<start_of_turn>",
        "<end_of_turn>",
        "<|assistant|>",
        "<|im_start|>",
        "<|im_end|>",
        "<|system|>",
        "<|channel|>",
        "<channel|>",
        "<|eot_id|>",
        "<|user|>",
        "<|turn|>",
        "<|turn>",
        "<turn|>"
    };

    std::string text;
    text.reserve(raw_prompt.size());

    size_t i = 0;
    while (i < raw_prompt.size()) {
        if (raw_prompt[i] == '<') {
            bool matched = false;
            for (const auto token : kControlTokens) {
                if (starts_with_icase(raw_prompt, i, token)) {
                    i += token.size();
                    if ((token == "<turn|>" || token == "<channel|>") &&
                        i < raw_prompt.size() && raw_prompt[i] == '\n') {
                        ++i;
                    }
                    matched = true;
                    break;
                }
            }
            if (matched) continue;
        }
        text.push_back(raw_prompt[i]);
        ++i;
    }

    // Trim leading whitespace
    const size_t first = text.find_first_not_of(" \t\n\r");
    if (first == std::string::npos) return "";

    // Trim trailing whitespace
    const size_t last = text.find_last_not_of(" \t\n\r");
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
    std::string system_msg = sanitize_input(default_system);

    // Buscar mensaje system explícito si existe en los turnos
    for (const auto& t : turns) {
        if (normalize_role(t.role) == "system") {
            std::string cleaned_sys = sanitize_input(t.content);
            if (!cleaned_sys.empty()) {
                system_msg = std::move(cleaned_sys);
            }
            break;
        }
    }

    // Normalizar historial: descartar turnos model/assistant antes del primer user
    // y fusionar turnos consecutivos del mismo rol para mantener alternancia estricta.
    std::vector<ChatTurn> normalized;
    normalized.reserve(turns.size());
    bool seen_user = false;

    for (const auto& t : turns) {
        const std::string role = normalize_role(t.role);
        if (role == "system") continue;

        std::string clean = sanitize_input(t.content);
        if (clean.empty()) continue;

        if (!seen_user) {
            if (role != "user") {
                continue; // Ignorar saludos/avisos iniciales del asistente antes del primer turno user
            }
            seen_user = true;
        }

        if (!normalized.empty() && normalized.back().role == role) {
            normalized.back().content += "\n\n";
            normalized.back().content += clean;
        } else {
            normalized.push_back({role, std::move(clean)});
        }
    }

    std::ostringstream ss;

    if (style == PromptStyle::GEMMA_4) {
        bool first_user = true;
        for (const auto& t : normalized) {
            if (t.role == "user") {
                ss << "<|turn>user\n";
                if (first_user && !system_msg.empty()) {
                    ss << system_msg << "\n\n";
                    first_user = false;
                }
                ss << t.content << "<turn|>\n";
            } else {
                ss << "<|turn>model\n" << t.content << "<turn|>\n";
            }
        }
        ss << "<|turn>model\n";
    } else if (style == PromptStyle::GEMMA) {
        bool first_user = true;
        for (const auto& t : normalized) {
            if (t.role == "user") {
                ss << "<start_of_turn>user\n";
                if (first_user && !system_msg.empty()) {
                    ss << system_msg << "\n\n";
                    first_user = false;
                }
                ss << t.content << "<end_of_turn>\n";
            } else {
                ss << "<start_of_turn>model\n" << t.content << "<end_of_turn>\n";
            }
        }
        ss << "<start_of_turn>model\n";
    } else if (style == PromptStyle::CHATML || style == PromptStyle::AUTO) {
        if (!system_msg.empty()) {
            ss << "<|im_start|>system\n" << system_msg << "<|im_end|>\n";
        }
        for (const auto& t : normalized) {
            const std::string role = (t.role == "model") ? "assistant" : t.role;
            ss << "<|im_start|>" << role << "\n" << t.content << "<|im_end|>\n";
        }
        ss << "<|im_start|>assistant\n";
    } else { // LLAMA3
        ss << "<|begin_of_text|>";
        if (!system_msg.empty()) {
            ss << "<|start_header_id|>system<|end_header_id|>\n\n" << system_msg << "<|eot_id|>";
        }
        for (const auto& t : normalized) {
            const std::string role = (t.role == "model") ? "assistant" : t.role;
            ss << "<|start_header_id|>" << role << "<|end_header_id|>\n\n" << t.content << "<|eot_id|>";
        }
        ss << "<|start_header_id|>assistant<|end_header_id|>\n\n";
    }

    return ss.str();
}

} // namespace kofu
