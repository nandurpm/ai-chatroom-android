# AI Chatroom web app

This repository also contains a responsive Next.js web client at the repository root. It coexists with the Android project because the web routes live under `src/app/` while Android remains under `app/`.

## Web features

- Choose **1–8 active AI participants** in one room.
- Add multiple participants using the same provider if desired.
- Configure each participant's display name, provider, model ID and API key.
- Friendly and Expert reply modes.
- Sequential turns: each later AI can see the user message and replies already produced by earlier AIs.
- Stop a running turn, clear the room, copy replies, and keep chat/settings locally in the browser.
- API keys are kept in `sessionStorage` only (current browser tab/session) and are sent only to this app's `/api/chat` route for the selected provider request. They are not written to the repository or bundled into client JavaScript.

Supported web providers are currently Groq, OpenRouter, Google AI Studio, Cerebras, Hugging Face Inference Providers and NVIDIA. The Vercel server route uses a fixed provider allow-list rather than accepting arbitrary URLs, avoiding an SSRF-capable public proxy. Local Ollama is intentionally Android-only for now because a Vercel function cannot reach a user's LAN/localhost.

## Run locally

Requires a current Node.js LTS release.

```bash
npm install
npm run dev
```

Open `http://localhost:3000`.

Production build:

```bash
npm run build
npm start
```

## Deploy on Vercel

Import this GitHub repository into Vercel and leave **Root Directory** at the repository root. Vercel should detect **Next.js** from `package.json`; no Gradle/Android build command is used for the web deployment.

If an existing Vercel project has custom build settings from an earlier attempt, reset **Framework Preset** to Next.js and clear custom Build/Output Directory overrides, then redeploy the latest commit.
