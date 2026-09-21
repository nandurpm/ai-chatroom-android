package com.example.aichatroom.domain

/** Data only: choosing a preset never purchases credits or selects a paid fallback. Checked 2026-09-21. */
data class ProviderPreset(val name: String, val config: ProviderConfig, val terms: String, val source: String)
object ProviderPresets {
    val all = listOf(
        ProviderPreset("Groq", ProviderConfig("https://api.groq.com/openai/v1/", "openai/gpt-oss-20b", contextTokens = 8192),
            "Free plan; per-model request and token limits apply.", "https://console.groq.com/docs/rate-limits"),
        ProviderPreset("OpenRouter Free", ProviderConfig("https://openrouter.ai/api/v1/", "openrouter/free"),
            "Zero-priced free model router; quotas and available models vary.", "https://openrouter.ai/openrouter/free"),
        ProviderPreset("Google AI Studio", ProviderConfig("https://generativelanguage.googleapis.com/", "gemini-3.5-flash-lite", RequestShape.GEMINI, AuthStyle.GOOGLE_KEY),
            "Free tier subject to account/region quotas; free-tier data may improve Google products.", "https://ai.google.dev/gemini-api/docs/pricing"),
        ProviderPreset("Local Ollama", ProviderConfig("http://10.0.2.2:11434/v1/", "llama3.2:3b", authStyle = AuthStyle.NONE, contextTokens = 4096, maxTokens = 1024, allowLocalHttp = true),
            "No API key or hosted fee. Requires your own running Ollama server and downloaded model; LAN traffic is unencrypted.", "https://docs.ollama.com/api/openai-compatibility"),
        ProviderPreset("Cerebras", ProviderConfig("https://api.cerebras.ai/v1/", "gpt-oss-120b"),
            "Free and paid account limits vary. HTTP 402 means this account needs available credits or billing setup; check the Cerebras Cloud Console.", "https://inference-docs.cerebras.ai/console/account-billing"),
        ProviderPreset("Hugging Face · tiny credit", ProviderConfig("https://router.huggingface.co/v1/", "Qwen/Qwen2.5-Coder-32B-Instruct"),
            "$0.10/month free-user credit, subject to change; unsuitable for sustained group chat. Check live model availability.", "https://huggingface.co/docs/inference-providers/pricing"),
        ProviderPreset("NVIDIA", AgentProfile.NVIDIA.providerConfig,
            "Account/quota dependent; check model availability, especially the legacy fallback.", "https://build.nvidia.com/"),
        ProviderPreset("Custom compatible endpoint", ProviderConfig(), "Enter your API root (including /v1 where needed), model and auth style.", "")
    )
}
