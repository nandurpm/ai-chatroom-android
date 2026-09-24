package com.example.aichatroom.domain

/** Data only: choosing a preset never purchases credits or selects a paid fallback. Checked 2026-09-24. */
data class ProviderPreset(val name: String, val config: ProviderConfig, val terms: String, val source: String)
object ProviderPresets {
    val all = listOf(
        ProviderPreset("Groq", ProviderConfig("https://api.groq.com/openai/v1/", "qwen/qwen3.8-27b", contextTokens = 8192),
            "Free/developer plan; per-model request and token limits apply.", "https://console.groq.com/docs/models"),
        ProviderPreset("OpenRouter Free", ProviderConfig("https://openrouter.ai/api/v1/", "openrouter/free"),
            "Zero-priced free model router; quotas and available models vary.", "https://openrouter.ai/openrouter/free"),
        ProviderPreset("Google AI Studio", ProviderConfig("https://generativelanguage.googleapis.com/", "gemini-3.8-flash", RequestShape.GEMINI, AuthStyle.GOOGLE_KEY),
            "Gemini API via Google AI Studio; account and regional quotas apply.", "https://ai.google.dev/gemini-api/docs/models/gemini-3.8-flash"),
        ProviderPreset("Local Ollama", ProviderConfig("http://10.0.2.2:11434/v1/", "llama3.2:3b", authStyle = AuthStyle.NONE, contextTokens = 4096, maxTokens = 1024, allowLocalHttp = true),
            "No API key or hosted fee. Requires your own running Ollama server and downloaded model; LAN traffic is unencrypted.", "https://docs.ollama.com/api/openai-compatibility"),
        ProviderPreset("Hugging Face", ProviderConfig("https://router.huggingface.co/v1/", "openai/gpt-oss-120b:fastest"),
            "Inference Providers router; token permissions, provider availability and credits apply.", "https://huggingface.co/docs/inference-providers/index"),
        ProviderPreset("NVIDIA", AgentProfile.NVIDIA.providerConfig,
            "NVIDIA hosted NIM free endpoint; account quotas apply.", "https://build.nvidia.com/nvidia/nemotron-3-ultra-550b-a55b"),
        ProviderPreset("Custom compatible endpoint", ProviderConfig(), "Enter your API root (including /v1 where needed), model and auth style.", "")
    )
}
