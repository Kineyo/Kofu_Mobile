package com.kofu.assistant.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class OllamaGenerateRequest(
    @SerialName("model") val model: String = "llama3.2:1b",
    @SerialName("prompt") val prompt: String,
    @SerialName("system") val system: String? = null,
    @SerialName("stream") val stream: Boolean = true
)

@Serializable
data class OllamaGenerateResponse(
    @SerialName("model") val model: String = "",
    @SerialName("response") val response: String = "",
    @SerialName("done") val done: Boolean = false
)
