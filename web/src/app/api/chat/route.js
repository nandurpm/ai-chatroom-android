import { NextResponse } from "next/server";
import { PROVIDER_MAP } from "@/lib/providers";

export const runtime = "nodejs";
export const maxDuration = 90;

const MAX_HISTORY = 80;
const MAX_TEXT = 40_000;
const OPENROUTER_FALLBACK_MODEL = process.env.OPENROUTER_FALLBACK_MODEL?.trim() || "openrouter/free";
const DEFAULT_SYSTEM_PROMPT =
  "You are one participant in a group AI chatroom. Reply to the user's latest request while considering useful points from other participants. Do not pretend to be the other agents. Be concise unless detail is useful.";

const SERVER_KEY_ENV = {
  groq: ["GROQ_API_KEY"],
  openrouter: ["OPENROUTER_API_KEY"],
  google: ["GOOGLE_API_KEY", "GEMINI_API_KEY"],
  cerebras: ["CEREBRAS_API_KEY"],
  huggingface: ["HUGGINGFACE_API_KEY", "HF_API_KEY", "HF_TOKEN"],
  nvidia: ["NVIDIA_API_KEY"],
};

class ProviderHttpError extends Error {
  constructor(status, providerId, detail = "") {
    super(`PROVIDER_HTTP_${status}`);
    this.name = "ProviderHttpError";
    this.status = status;
    this.providerId = providerId;
    this.detail = detail;
  }
}

function serverApiKey(providerId) {
  return (SERVER_KEY_ENV[providerId] || [])
    .map((name) => process.env[name]?.trim())
    .find(Boolean) || "";
}

function configuredServerProviders() {
  return Object.fromEntries(Object.keys(SERVER_KEY_ENV).map((providerId) => [providerId, Boolean(serverApiKey(providerId))]));
}

function cleanText(value, max = MAX_TEXT) {
  return typeof value === "string" ? value.slice(0, max) : "";
}

function normalizeAnswerText(value) {
  const text = cleanText(value).trim();
  if (!text) return "";
  if (text.startsWith("{") && text.endsWith("}")) {
    try {
      const parsed = JSON.parse(text);
      if (typeof parsed?.text === "string" && parsed.text.trim()) return cleanText(parsed.text).trim();
      if (typeof parsed?.message === "string" && parsed.message.trim()) return cleanText(parsed.message).trim();
    } catch {}
  }
  return text;
}

function authHeaders(provider, apiKey) {
  if (!apiKey) throw new Error("API_KEY_REQUIRED");
  if (provider.auth === "google-key") return { "x-goog-api-key": apiKey };
  return { Authorization: `Bearer ${apiKey}` };
}

function sanitizeHistory(history) {
  if (!Array.isArray(history)) return [];
  return history.slice(-MAX_HISTORY).map((message) => ({
    speakerId: cleanText(message?.speakerId, 120),
    speakerName: cleanText(message?.speakerName, 120) || "Participant",
    text: cleanText(message?.text),
    error: Boolean(message?.error),
  }));
}

function transcriptText(message) {
  return `${message.speakerName}: ${message.text}`;
}

function openAiMessages(history, agent, systemPrompt) {
  const messages = [{ role: "system", content: systemPrompt }];
  for (const message of history) {
    if (message.error || !message.text) continue;
    messages.push({
      role: message.speakerId === agent.id ? "assistant" : "user",
      content: message.speakerId === agent.id ? message.text : transcriptText(message),
    });
  }
  return messages;
}

function geminiContents(history, agent) {
  const contents = [];
  for (const message of history) {
    if (message.error || !message.text) continue;
    const role = message.speakerId === agent.id ? "model" : "user";
    const text = message.speakerId === agent.id ? message.text : transcriptText(message);
    const previous = contents.at(-1);
    if (previous?.role === role) previous.parts.push({ text });
    else contents.push({ role, parts: [{ text }] });
  }
  return contents;
}

function friendlyError(status) {
  if (status === 401) return "API key rejected by the provider.";
  if (status === 402) return "Provider credits or billing are required for this request.";
  if (status === 403) return "API key lacks permission for this provider or model.";
  if (status === 429) return "Provider rate limit or quota reached.";
  if (status === 400 || status === 413) return "The provider rejected the request or model configuration.";
  if (status === 404 || status === 410) return "Model not found or no longer available.";
  if (status >= 500) return "The AI provider is temporarily unavailable.";
  return `Provider request failed with HTTP ${status}.`;
}

async function providerFailure(response, provider) {
  let detail = "";
  try {
    const raw = await response.text();
    if (raw) {
      try {
        const parsed = JSON.parse(raw);
        detail = cleanText(
          parsed?.error?.message || parsed?.error?.detail || parsed?.message || parsed?.detail || "",
          300,
        ).trim();
      } catch {
        detail = cleanText(raw, 300).trim();
      }
    }
  } catch {}
  throw new ProviderHttpError(response.status, provider.id, detail);
}

function providerErrorMessage(error, provider) {
  if (error instanceof ProviderHttpError) {
    const base = `${provider?.label || "Provider"}: ${friendlyError(error.status)}`;
    return error.detail ? `${base} ${error.detail}` : base;
  }
  if (error?.name === "TimeoutError" || error?.name === "AbortError") return "The provider timed out before replying.";
  if (error instanceof Error && error.message === "EMPTY_RESPONSE") return "The provider returned no answer text.";
  return "Unable to complete this AI reply.";
}

function canFallback(error, providerId) {
  if (providerId === "openrouter" || !serverApiKey("openrouter")) return false;
  if (error?.name === "AbortError" || error?.name === "TimeoutError") return false;
  return true;
}

export async function GET() {
  return NextResponse.json({
    providers: configuredServerProviders(),
    fallback: Boolean(serverApiKey("openrouter")),
  }, {
    headers: { "Cache-Control": "no-store" },
  });
}

async function callOpenAi(provider, agent, history, systemPrompt, apiKey, maxTokens, signal) {
  const response = await fetch(`${provider.baseUrl}chat/completions`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(provider, apiKey),
    },
    body: JSON.stringify({
      model: agent.model,
      messages: openAiMessages(history, agent, systemPrompt),
      max_tokens: maxTokens,
      stream: false,
    }),
    redirect: "manual",
    cache: "no-store",
    signal,
  });

  if (!response.ok) await providerFailure(response, provider);
  const payload = await response.json();
  const answer = payload?.choices?.[0]?.message;
  const content = typeof answer?.content === "string"
    ? answer.content
    : Array.isArray(answer?.content)
      ? answer.content.map((part) => part?.text || "").join("\n")
      : answer?.refusal;
  const text = normalizeAnswerText(content);
  if (!text) throw new Error("EMPTY_RESPONSE");
  return text;
}

async function callGemini(provider, agent, history, systemPrompt, apiKey, maxTokens, signal) {
  const url = `${provider.baseUrl}v1beta/models/${encodeURIComponent(agent.model)}:generateContent`;
  const response = await fetch(url, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      ...authHeaders(provider, apiKey),
    },
    body: JSON.stringify({
      contents: geminiContents(history, agent),
      systemInstruction: { parts: [{ text: systemPrompt }] },
      generationConfig: { maxOutputTokens: maxTokens },
    }),
    redirect: "manual",
    cache: "no-store",
    signal,
  });

  if (!response.ok) await providerFailure(response, provider);
  const payload = await response.json();
  const text = normalizeAnswerText(
    payload?.candidates?.[0]?.content?.parts
      ?.filter((part) => part?.thought !== true)
      .map((part) => part?.text || "")
      .join("\n"),
  );
  if (!text) throw new Error("EMPTY_RESPONSE");
  return text;
}

async function callProvider(provider, agent, history, systemPrompt, apiKey, maxTokens, signal) {
  return provider.shape === "gemini"
    ? callGemini(provider, agent, history, systemPrompt, apiKey, maxTokens, signal)
    : callOpenAi(provider, agent, history, systemPrompt, apiKey, maxTokens, signal);
}

async function callOpenRouterFallback(agent, history, roomPrompt, maxTokens, signal, failedProvider) {
  const provider = PROVIDER_MAP.openrouter;
  const apiKey = serverApiKey("openrouter");
  const fallbackAgent = { ...agent, model: OPENROUTER_FALLBACK_MODEL };
  const systemPrompt = `${roomPrompt}\n\nYou are ${agent.name || "an AI participant"}. The configured ${failedProvider.label} request is unavailable, so you are temporarily replying through OpenRouter. Answer the user's request normally. Do not claim that this response came from ${failedProvider.label}. Reply in plain text only; do not wrap the answer in JSON and do not repeat your speaker name.`;
  return callOpenAi(provider, fallbackAgent, history, systemPrompt, apiKey, maxTokens, signal);
}

export async function POST(request) {
  try {
    const body = await request.json();
    const provider = PROVIDER_MAP[body?.agent?.providerId];
    if (!provider) return NextResponse.json({ error: "Unsupported provider." }, { status: 400 });

    const agent = {
      id: cleanText(body?.agent?.id, 120),
      name: cleanText(body?.agent?.name, 120),
      model: cleanText(body?.agent?.model, 220),
    };
    if (!agent.id || !agent.model) {
      return NextResponse.json({ error: "Participant configuration is incomplete." }, { status: 400 });
    }

    // Prefer Vercel environment keys. Browser-entered keys are only used when no server key exists.
    const apiKey = serverApiKey(provider.id) || cleanText(body?.apiKey, 10_000);
    if (!apiKey) return NextResponse.json({ error: "Add an API key for this participant." }, { status: 400 });

    const maxTokens = Math.min(Math.max(Number(body?.maxTokens) || 2048, 128), 8192);
    const history = sanitizeHistory(body?.history);
    const roomPrompt = cleanText(body?.systemPrompt, 8_000) || DEFAULT_SYSTEM_PROMPT;
    const systemPrompt = `${roomPrompt}\n\nYou are ${agent.name || "an AI participant"}. Messages from other room participants are prefixed with their speaker name. Reply in plain text only; do not wrap the answer in JSON and do not repeat your speaker name.`;
    const signal = AbortSignal.any([request.signal, AbortSignal.timeout(84_000)]);

    try {
      const text = await callProvider(provider, agent, history, systemPrompt, apiKey, maxTokens, signal);
      return NextResponse.json({ text });
    } catch (error) {
      if (canFallback(error, provider.id)) {
        try {
          const text = await callOpenRouterFallback(agent, history, roomPrompt, maxTokens, signal, provider);
          const reason = error instanceof ProviderHttpError ? friendlyError(error.status) : "Native provider request failed.";
          const fallbackText = `Fallback via OpenRouter (${reason})\n\n${text}`;
          return NextResponse.json({
            text: fallbackText,
            fallback: {
              from: provider.id,
              via: "openrouter",
              reason,
            },
          });
        } catch {
          // Preserve the native provider error if the fallback also fails.
        }
      }
      throw error;
    }
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (message === "API_KEY_REQUIRED") {
      return NextResponse.json({ error: "Add an API key for this participant." }, { status: 400 });
    }
    if (error instanceof ProviderHttpError) {
      const provider = PROVIDER_MAP[error.providerId];
      return NextResponse.json({ error: providerErrorMessage(error, provider) }, { status: 502 });
    }
    if (message === "EMPTY_RESPONSE") {
      return NextResponse.json({ error: "The provider returned no answer text." }, { status: 502 });
    }
    if (error?.name === "TimeoutError" || error?.name === "AbortError") {
      return NextResponse.json({ error: "The provider timed out before replying." }, { status: 504 });
    }
    return NextResponse.json({ error: "Unable to complete this AI reply." }, { status: 500 });
  }
}
