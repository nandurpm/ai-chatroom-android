package com.example.aichatroom.domain

import androidx.compose.ui.graphics.Color
import java.net.URI

/** Describes wire format independently of provider branding. Secrets live only in SettingsStore. */
enum class RequestShape { OPENAI, GEMINI }
enum class AuthStyle { BEARER, GOOGLE_KEY, CUSTOM, NONE }
data class ProviderConfig(
    val baseUrl: String = "https://api.openai.com/v1/",
    val modelId: String = "",
    val shape: RequestShape = RequestShape.OPENAI,
    val authStyle: AuthStyle = AuthStyle.BEARER,
    val customHeader: String = "X-API-Key",
    val maxTokens: Int? = null,
    val thinking: Boolean? = null,
    val thinkingLevel: String? = null,
    val fallbackModel: String = "",
    val contextTokens: Int = 16384,
    val allowLocalHttp: Boolean = false
) {
    /** Reject credential-bearing URLs and remote cleartext before opening a socket. */
    fun validate() {
        val uri = runCatching { URI(baseUrl) }.getOrElse { throw IllegalArgumentException("Enter a valid API base URL.") }
        require(uri.userInfo == null && uri.rawQuery == null && uri.rawFragment == null) { "Use a base URL without credentials, query or fragment." }
        val host = uri.host.orEmpty()
        val ipv4 = host.split('.').mapNotNull { it.toIntOrNull()?.takeIf { n -> n in 0..255 } }
        val local = host == "localhost" || host == "[::1]" ||
            (ipv4.size == 4 && (ipv4[0] == 127 || ipv4[0] == 10 ||
                (ipv4[0] == 192 && ipv4[1] == 168) || (ipv4[0] == 172 && ipv4[1] in 16..31)))
        require(host.isNotBlank() && (uri.scheme == "https" ||
            (uri.scheme == "http" && allowLocalHttp && local && authStyle == AuthStyle.NONE))) {
            "Use HTTPS, or opt into keyless HTTP for a local IP."
        }
        require(modelId.isNotBlank() && modelId.length <= 200) { "Enter a model ID." }
        require(contextTokens in 1024..2_000_000 && (maxTokens == null || maxTokens in 1 until contextTokens)) { "Output budget must fit inside context budget." }
        require(customHeader.matches(Regex("[A-Za-z][A-Za-z0-9-]*")) && customHeader.lowercase() !in
            setOf("host", "content-length", "content-type", "connection", "cookie")) { "Invalid authentication header." }
    }
}

/** Stable IDs identify history and encrypted credentials; names and avatars remain editable. */
data class AgentProfile(val id: String, val displayName: String, val color: Color,
    val avatarLetter: String, val providerConfig: ProviderConfig,
    val enabled: Boolean = true, val archived: Boolean = false) {
    val label get() = displayName
    val name get() = id
    companion object {
        // These are migration/new-install seeds, never a closed roster or provider dispatch table.
        val USER = AgentProfile("USER", "You", Color(0xFF6750A4), "Y", ProviderConfig(), false)
        val NVIDIA = AgentProfile("NVIDIA", "NVIDIA", Color(0xFF287D44), "N", ProviderConfig(
            "https://integrate.api.nvidia.com/v1/", "nvidia/nemotron-3-super-120b-a12b",
            fallbackModel = "qwen/qwen3.5-397b-a17b"))
        val GEMINI = AgentProfile("GEMINI", "Gemini", Color(0xFF356AC3), "G", ProviderConfig(
            "https://generativelanguage.googleapis.com/", "gemini-3.6-flash", RequestShape.GEMINI, AuthStyle.GOOGLE_KEY))
        val CHATGPT = AgentProfile("CHATGPT", "ChatGPT", Color(0xFF16836A), "C", ProviderConfig(modelId = "gpt-4o-mini"), false, true)
        fun valueOf(id: String) = listOf(USER, NVIDIA, GEMINI, CHATGPT).find { it.id == id }
            ?: AgentProfile(id, id, Color.Gray, id.take(1), ProviderConfig(), false, true)
    }
}
