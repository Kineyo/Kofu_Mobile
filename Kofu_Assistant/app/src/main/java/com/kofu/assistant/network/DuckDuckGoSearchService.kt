package com.kofu.assistant.network

import com.kofu.assistant.privacy.PrivacySanitizer
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.DefaultRequest
import io.ktor.client.request.forms.submitForm
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.parameters

class DuckDuckGoSearchService(
    private val client: HttpClient = createPrivacyClient()
) {

    companion object {
        fun createPrivacyClient(): HttpClient {
            return HttpClient(Android) {
                engine {
                    connectTimeout = 15_000
                    socketTimeout = 15_000
                }

                // Force privacy headers: non-identifiable User-Agent and no cache/cookies
                install(DefaultRequest) {
                    header(HttpHeaders.UserAgent, "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                    header(HttpHeaders.CacheControl, "no-cache, no-store, must-revalidate")
                    header(HttpHeaders.Pragma, "no-cache")
                    header(HttpHeaders.Expires, "0")
                    header("Sec-GPC", "1") // Global Privacy Control
                    header("DNT", "1")     // Do Not Track
                }
            }
        }
    }

    /**
     * Executes privacy-preserving search on DuckDuckGo Lite.
     * Sanitizes raw input prompt using PrivacySanitizer and sends EXCLUSIVELY the processed text.
     */
    suspend fun searchPrivacy(rawInput: String): String {
        // 1. Sanitize input prompt strictly using local PrivacySanitizer
        val sanitizedQuery = PrivacySanitizer.sanitizePrompt(rawInput)

        if (sanitizedQuery.isBlank()) {
            return "Consulta vacía tras aplicar la sanitización de privacidad."
        }

        // 2. HTTP POST Request to DuckDuckGo Lite with EXCLUSIVELY sanitized text
        val response = client.submitForm(
            url = "https://lite.duckduckgo.com/lite/",
            formParameters = parameters {
                append("q", sanitizedQuery)
            }
        )

        return response.bodyAsText()
    }
}
