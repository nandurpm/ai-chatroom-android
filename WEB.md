# AI Chatroom web app

The web client is isolated under `web/` so it does not conflict with the Android Gradle module at the repository-root `app/` directory.

## Features

- Choose **1–8 active AI participants** in one room.
- Configure each participant's display name, provider, model ID and API key.
- Use Groq, OpenRouter, Google AI Studio, Cerebras, Hugging Face Inference Providers, or NVIDIA.
- Friendly and Expert reply styles.
- **Fast mode:** starts every enabled AI at the same time and shows each reply as soon as that provider finishes.
- **Discussion mode:** sequential turns so later AIs can see earlier AI replies from the same turn.
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
- **Production Branch:** `main`

Then redeploy the latest `main` commit.

The `web/` root is important because the Android project already has a root-level `app/` directory. Next.js treats `app/` as a special routing directory, so deploying the repository root can cause Next.js to resolve the Android module instead of the real web routes.

## If the `*.vercel.app` URL shows `404 NOT_FOUND`

A Vercel-branded root-page 404 usually means the hostname is not currently serving an active production deployment, or the project is still deployed with the wrong root/configuration.

Check these in the Vercel dashboard:

1. **Project → Settings → Build and Deployment**: confirm Root Directory is exactly `web` and Framework Preset is Next.js.
2. **Project → Deployments**: redeploy the latest `main` commit. The deployment must finish successfully.
3. If the latest successful deployment is only a Preview, use **Promote to Production**.
4. **Project → Settings → Domains**: confirm the intended `*.vercel.app` hostname is assigned to this project/current production deployment. If Vercel generated a different production hostname, use that hostname or reassign the intended one.
5. Do not set a custom Output Directory for this Next.js project.

Local Ollama remains Android-only because a Vercel server function cannot reach a user's LAN/localhost.

## Cerebras HTTP 402

The app treats HTTP 402 as an account billing/credit problem rather than retrying it. Check the Cerebras Cloud Console billing/credit balance. Cerebras documents trial credits as time/credit limited; normal API rate-limit exhaustion uses HTTP 429.
