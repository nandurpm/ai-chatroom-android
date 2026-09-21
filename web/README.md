# AI Chatroom web app

The web client is isolated under `web/` so it does not conflict with the Android Gradle module at the repository-root `app/` directory.

## Features

- Choose **1–8 active AI participants** in one room.
- Configure each participant's display name, provider, model ID and API key.
- Use Groq, OpenRouter, Google AI Studio, Cerebras, Hugging Face Inference Providers, or NVIDIA.
- Friendly and Expert reply modes.
- Sequential turns so later AIs can see earlier AI replies.
- Stop a running turn, clear the room, copy replies, and keep chat/settings locally in the browser.
- API keys are stored in `sessionStorage` for the current browser tab/session and sent only to `/api/chat` for the selected provider request.

## Run locally

```bash
cd web
npm install
npm run dev
```

Open `http://localhost:3000`.

Production build:

```bash
cd web
npm install
npm run build
npm start
```

## Deploy on Vercel

Import this GitHub repository into Vercel and set:

- **Root Directory:** `web`
- **Framework Preset:** Next.js
- **Build Command:** leave default (`next build`)
- **Output Directory:** leave default
- **Install Command:** leave default

Then redeploy the latest commit.

The `web/` root is important because the Android project already has a root-level `app/` directory. Next.js treats `app/` as a special routing directory, so deploying the repository root can cause Next.js to resolve the Android module instead of the real web routes.

Local Ollama remains Android-only because a Vercel server function cannot reach a user's LAN/localhost.
