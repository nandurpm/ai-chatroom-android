export const PROVIDERS = [
  {
    id: "groq",
    label: "Groq",
    shortLabel: "G",
    baseUrl: "https://api.groq.com/openai/v1/",
    model: "qwen/qwen3.8-27b",
    shape: "openai",
    auth: "bearer",
    envKey: "GROQ_API_KEY",
    accent: "#f97316",
    note: "Groq-hosted Qwen 3.8 27B. Provider quotas apply.",
  },
  {
    id: "openrouter",
    label: "OpenRouter Free",
    shortLabel: "OR",
    baseUrl: "https://openrouter.ai/api/v1/",
    model: "openrouter/free",
    shape: "openai",
    auth: "bearer",
    envKey: "OPENROUTER_API_KEY",
    accent: "#8b5cf6",
    note: "Routes to currently available zero-priced models. Availability varies.",
  },
  {
    id: "google",
    label: "Google AI Studio",
    shortLabel: "Gm",
    baseUrl: "https://generativelanguage.googleapis.com/",
    model: "gemini-3.8-flash",
    shape: "gemini",
    auth: "google-key",
    envKey: "GEMINI_API_KEY",
    accent: "#4285f4",
    note: "Gemini 3.8 Flash via Google AI Studio. Account and regional quotas apply.",
  },
  {
    id: "huggingface",
    label: "Hugging Face",
    shortLabel: "HF",
    baseUrl: "https://router.huggingface.co/v1/",
    model: "openai/gpt-oss-120b:fastest",
    shape: "openai",
    auth: "bearer",
    envKey: "HF_TOKEN",
    accent: "#eab308",
    note: "Inference Providers router using the fastest available provider for GPT-OSS 120B.",
  },
  {
    id: "nvidia",
    label: "NVIDIA",
    shortLabel: "N",
    baseUrl: "https://integrate.api.nvidia.com/v1/",
    model: "nvidia/nemotron-3-ultra-550b-a55b",
    shape: "openai",
    auth: "bearer",
    envKey: "NVIDIA_API_KEY",
    accent: "#76b900",
    note: "NVIDIA Nemotron 3 Ultra hosted free endpoint. Account quotas apply.",
  },
];

export const PROVIDER_MAP = Object.fromEntries(PROVIDERS.map((provider) => [provider.id, provider]));

export function makeAgent(index = 0) {
  const provider = PROVIDERS[index % PROVIDERS.length];
  return {
    id: `agent-${Date.now()}-${index}-${Math.random().toString(36).slice(2, 8)}`,
    name: provider.label.replace(" Free", ""),
    avatar: provider.shortLabel,
    providerId: provider.id,
    model: provider.model,
    enabled: true,
    accent: provider.accent,
    apiKey: "",
  };
}
