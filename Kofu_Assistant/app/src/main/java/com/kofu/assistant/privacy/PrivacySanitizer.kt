package com.kofu.assistant.privacy

object PrivacySanitizer {

    // Regex patterns for sensitive data detection
    private val emailRegex = Regex("[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}", RegexOption.IGNORE_CASE)
    private val phoneRegex = Regex("(?:\\+?\\d{1,3}[- .]?)?\\(?\\d{2,4}\\)?[- .]?\\d{3,4}[- .]?\\d{3,4}")
    
    // API keys, tokens, passwords, auth headers, UUIDs, numeric IDs, credit cards, DNI/RFC/SSN patterns
    private val apiKeyTokenRegex = Regex("(?i)(api[_-]?key|bearer|token|secret|password|passwd|pwd)[:=]\\s*[^\\s]+")
    private val uuidRegex = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val idOrNumericPersonalRegex = Regex("\\b(?:DNI|SSN|RFC|CURP|ID|CI)\\b[:=]?\\s*\\w+", RegexOption.IGNORE_CASE)
    private val genericLongNumbersRegex = Regex("\\b\\d{8,19}\\b")

    // Locations / Addresses patterns (e.g., Calle..., Av..., Ciudad...)
    private val addressLocationRegex = Regex("(?i)\\b(?:calle|avenida|av\\.|blvd|boulevard|carrera|cra|colonia|col\\.|postal|cp|c\\.p\\.)\\s+[\\w\\s\\d#.-]+", RegexOption.IGNORE_CASE)

    // Proper name patterns (e.g. "Me llamo Juan Pérez", "Soy Maria Gomez", etc.)
    private val explicitNameIntroRegex = Regex("(?i)\\b(?:me llamo|mi nombre es|soy|atentamente|saludos|attt?)\\s+([A-ZÁÉÍÓÚÑa-záéíóúñ]+(?:\\s+[A-ZÁÉÍÓÚÑa-záéíóúñ]+){1,3})", RegexOption.IGNORE_CASE)

    // Common Spanish & English stop words for key concept extraction
    private val stopWords = setOf(
        "de", "la", "que", "el", "en", "y", "a", "los", "del", "se", "por", "un", "para", "con", "no", "una",
        "su", "al", "lo", "como", "más", "pero", "sus", "le", "ya", "o", "este", "sí", "porque", "esta",
        "entre", "cuando", "muy", "sin", "sobre", "también", "me", "hasta", "hay", "donde", "quien",
        "desde", "todo", "nos", "durante", "todos", "uno", "les", "ni", "contra", "otros", "ese", "eso",
        "ante", "ellos", "e", "esto", "mí", "antes", "algunos", "qué", "unos", "yo", "otro", "otras",
        "otra", "él", "tanto", "esa", "estos", "mucho", "quienes", "nada", "muchos", "cual", "poco",
        "ella", "estar", "estas", "algunas", "algo", "nosotros", "mi", "mis", "quisiera", "saber",
        "buscar", "busca", "porfavor", "por", "favor", "necesito", "dame", "hola", "buenos", "días",
        "tardes", "noches", "the", "is", "at", "which", "on", "a", "an", "and", "or", "in", "to", "for",
        "with", "about", "against", "between", "into", "through", "during", "before", "after", "above",
        "below", "from", "up", "down", "off", "over", "under", "again", "further"
    )

    /**
     * Sanitizes input prompt by detecting and replacing sensitive information:
     * - Replaces email, phone numbers, addresses, keys, IDs with [REDACTED]
     * - Replaces or removes proper name patterns
     * - Extracts strictly key concepts for search query.
     */
    fun sanitizePrompt(input: String): String {
        if (input.isBlank()) return ""

        var sanitized = input

        // 1. Redact API keys and tokens
        sanitized = apiKeyTokenRegex.replace(sanitized, "[REDACTED]")

        // 2. Redact UUIDs
        sanitized = uuidRegex.replace(sanitized, "[REDACTED]")

        // 3. Redact Emails
        sanitized = emailRegex.replace(sanitized, "[REDACTED]")

        // 4. Redact Phone numbers
        sanitized = phoneRegex.replace(sanitized, "[REDACTED]")

        // 5. Redact IDs and numeric personal data (credit card / DNI / RFC / long numbers)
        sanitized = idOrNumericPersonalRegex.replace(sanitized, "[REDACTED]")
        sanitized = genericLongNumbersRegex.replace(sanitized, "[REDACTED]")

        // 6. Redact Addresses / Locations
        sanitized = addressLocationRegex.replace(sanitized, "[REDACTED]")

        // 7. Redact Explicit Name Intros
        sanitized = explicitNameIntroRegex.replace(sanitized) { matchResult ->
            val prefix = matchResult.groupValues[0].substringBefore(matchResult.groupValues[1])
            "$prefix [NOMBRE_REDACTADO]"
        }

        // 8. Extract key search concepts
        return extractKeyConcepts(sanitized)
    }

    /**
     * Extracts key concepts from sanitized text suitable for privacy-preserving web queries.
     */
    private fun extractKeyConcepts(sanitizedText: String): String {
        val textWithoutRedacted = sanitizedText
            .replace("[REDACTED]", "")
            .replace("[NOMBRE_REDACTADO]", "")

        val tokens = textWithoutRedacted
            .replace(Regex("[^a-zA-Z0-9áéíóúÁÉÍÓÚñÑ\\s]"), " ")
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { token ->
                token.isNotEmpty() && 
                token.length > 1 && 
                token.lowercase() !in stopWords
            }

        return tokens.joinToString(" ")
    }
}
