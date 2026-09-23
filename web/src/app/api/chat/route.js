import { NextResponse } from "next/server";
import { PROVIDER_MAP } from "@/lib/providers";

export const runtime = "nodejs";
export const maxDuration = 90;

const MAX_HISTORY = 80;
const MAX_TEXT = 40_000;
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

function openAiMessages(history, agent, systemPrompt) {
  const messages = [{ role: "system", content: systemPrompt }];
  for (const message of history) {
    if (message.error || !message.text) continue;
    messages.push({
      role: message.speakerId === agent.id ? "assistant" : "user",
      content: JSON.stringify({ speaker: message.speakerName, text: message.text }),
    });
  }
  return messages;
}

function geminiContents(history, agent) {
  const contents = [];
  for (const message of history) {
    if (message.error || !message.text) continue;
    const role = message.speakerId === agent.id ? "model" : "user";
    const text = JSON.stringify({ speaker: message.speakerName, text: message.text });
    const previous = contents.at(-1);
    if (previous?.role === role) previous.parts.push({ text });
    else contents.push({ role, parts: [{ text }] });
  }
  return contents;
}

function friendlyError(status) {
  if (status === 401 || status === 403) return "API key rejected or this model is not available to the account.";
  if (status === 429) return "Provider rate limit or quota reached. Try again after checking the account limits.";
  if (status === 400 || status === 413) return "The provider rejected the request. Check the model name or start a shorter chat.";
  if (status === 404 || status === 410) return "Model not found. Update the model ID for this participant.";
  if (status >= 500) return "The AI provider is temporarily unavailable.";
  return `Provider request failed with HTTP ${status}.`;
}

export async function GET() {
  return NextResponse.json({ providers: configuredServerProviders() }, {
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

  if (!response.ok) throw new Error(`HTTP_${response.status}`);
  const payload = await response.json();
  const answer = payload?.choices?.[0]?.message;
  const text = cleanText(answer?.content || answer?.refusal);
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

  if (!response.ok) throw new Error(`HTTP_${response.status}`);
  const payload = await response.json();
  const text = cleanText(
    payload?.candidates?.[0]?.content?.parts
      ?.filter((part) => part?.thought !== true)
      .map((part) => part?.text || "")
      .join("\n"),
  );
  if (!text) throw new Error("EMPTY_RESPONSE");
  return text;
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

    // A browser-entered key takes precedence; Vercel server keys never reach the client.
    const apiKey = cleanText(body?.apiKey, 10_000) || serverApiKey(provider.id);
    if (!apiKey) return NextResponse.json({ error: "Add an API key for this participant." }, { status: 400 });

    const maxTokens = Math.min(Math.max(Number(body?.maxTokens) || 2048, 128), 8192);
    const history = sanitizeHistory(body?.history);
    const roomPrompt = cleanText(body?.systemPrompt, 8_000) || DEFAULT_SYSTEM_PROMPT;
    const systemPrompt = `${roomPrompt}\n\nYou are ${agent.name || "an AI participant"}. The transcript encodes each message as JSON with speaker and text fields.`;
    const signal = AbortSignal.any([request.signal, AbortSignal.timeout(84_000)]);

    const text = provider.shape === "gemini"
      ? await callGemini(provider, agent, history, systemPrompt, apiKey, maxTokens, signal)
      : await callOpenAi(provider, agent, history, systemPrompt, apiKey, maxTokens, signal);

    return NextResponse.json({ text });
  } catch (error) {
    const message = error instanceof Error ? error.message : "";
    if (message === "API_KEY_REQUIRED") {
      return NextResponse.json({ error: "Add an API key for this participant." }, { status: 400 });
    }
    if (message.startsWith("HTTP_")) {
      const status = Number(message.slice(5));
      return NextResponse.json({ error: friendlyError(status) }, { status: 502 });
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
