# AI Chatroom — NVIDIA edition

Native Kotlin / Jetpack Compose group chat with a human, NVIDIA-hosted AI and Gemini. **No OpenAI API key or OpenAI credits are required.**

## Install

Open this repository's Releases, choose the newest NVIDIA build, and download **AI-Chatroom-NVIDIA.apk**. Requires Android 8.0 or newer.

This is a debug build. Each GitHub runner generates a new debug signing key. If an older installation rejects the update, keep it until you have copied any important chat text; uninstalling it removes its local history and API keys. Then install the new APK and enter your keys again. Signing keys for a stable production upgrade path are not configured.

## Configure

In Settings, enter your NVIDIA API key and Gemini API key. Keys are encrypted with Android Keystore AES-GCM; they are not built into the app. Blank fields retain saved keys, and Remove deletes a key. NVIDIA has a separate credential slot and never reads a saved OpenAI key.

| Participant | Default model |
| --- | --- |
| NVIDIA primary | `nvidia/nemotron-3-super-120b-a12b` |
| NVIDIA fallback | `qwen/qwen3.5-397b-a17b` |
| Gemini | `gemini-3.6-flash` |

Create your NVIDIA key through [NVIDIA Build](https://build.nvidia.com/nvidia/nemotron-3-super-120b-a12b) and your Gemini key through [Google AI Studio](https://aistudio.google.com/apikey). Free access is account/quota dependent, not unlimited.

The requested Qwen free endpoint is **deprecated** according to [its NVIDIA listing](https://build.nvidia.com/qwen/qwen3.5-397b-a17b). It remains the requested default fallback, but may also fail with 404/410. Both NVIDIA model fields are editable; select an active hosted model if needed. Google documents the requested [Gemini 3.6 Flash ID](https://ai.google.dev/gemini-api/docs/models/gemini-3.6-flash).

## Faster replies (v1.2)

Quick replies is enabled by default, including on existing settings. It requests no thinking for the default Nemotron/Qwen models (`chat_template_kwargs.enable_thinking=false`) and minimal thinking for Gemini 3.6 Flash (`generationConfig.thinkingConfig.thinkingLevel=MINIMAL`). Unknown/custom models omit these model-specific fields. See [NVIDIA thinking controls](https://build.nvidia.com/nvidia/nemotron-3-super-120b-a12b/modelcard) and [Gemini API controls](https://ai.google.dev/api/generate-content#ThinkingConfig).

Quick mode asks for concise replies and caps output at 2,048 tokens in Friendly Roast or 4,096 in Expert Mode. Disable Quick replies in Settings for provider-default reasoning and the original 8,192 token budget. Lower thinking may reduce quality for difficult reasoning tasks. Full history and sequential peer awareness are preserved; responses still appear after each complete answer.

Each provider has a 90-second deadline including retries and fallback. Timeout adds an inline error and lets the next AI reply. Stop replies cancels the active turn without deleting saved messages. Provider queues and network speed still affect latency; these changes are not a measured live speed guarantee.

## Fallback and shared conversation

The primary NVIDIA request uses `https://integrate.api.nvidia.com/v1/chat/completions`, Bearer authentication, and `max_tokens`. If it returns 404 or 410, the app automatically tries the configured fallback using the same system prompt, full transcript and NVIDIA key. It switches models at most once. A successful fallback is identified in the message. If both are unavailable, an inline error tells the user to change the model IDs.

401/403 do not trigger model fallback. 429, 5xx and network failures retain bounded retries on the same model. Cancellation always propagates. Gemini uses `generativelanguage.googleapis.com` and an `x-goog-api-key` header.

Each human message starts one bounded turn. An AI response is saved before invoking the next AI, so the second sees the first. About 25% of turns reverse the order. Friendly Roast and Expert Mode change the system prompts. Either participant can be muted. Room persists the transcript, and Flow updates the UI after each complete reply (not token-by-token). Clear chat cancels active work before removing messages. Historical ChatGPT messages retain their original labels.

Messages are stored in private app storage but the Room database is not independently encrypted. The entire non-error history is sent to enabled providers. Backups and screenshots are disabled. Clearing local history does not delete provider-side records.

## Build

Open this folder in Android Studio, use JDK 17, and install Android SDK 35. Gradle 8.11.1 and Android Gradle Plugin 8.9.2 are pinned.

```bash
# Linux/macOS
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug
```

```powershell
# Windows
.\gradlew.bat testDebugUnitTest assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

## Project organization

- `domain/Chat.kt`: models, preferences and system prompts.
- `domain/TurnEngine.kt`: sequential orchestration and cancellation.
- `data/ChatDatabase.kt`: Room DAO, entities and repository.
- `data/SettingsStore.kt`: credential encryption and provider settings.
- `network/Providers.kt`: Retrofit providers, transcript encoding, retries and NVIDIA fallback.
- `ui/ChatViewModel.kt`: state and turn lifecycle.
- `ui/ChatroomApp.kt`, `ui/Theme.kt`: Material 3 chat/settings screens and dark mode.
- `app/src/test`: 21 tests covering ordering, solo chat, peer visibility, cancellation, API contracts, retries, missing keys, 404/410 fallback and historical speaker identity.
- `.github/workflows/android.yml`: builds, runs tests, verifies APK signature and publishes private release assets. Release delivery avoids the account's exhausted Actions artifact storage.

Main Kotlin paths are under `app/src/main/java/com/example/aichatroom`.

## Verification

The CI release step runs only after all 21 tests and APK signature verification pass. Tests use MockWebServer with fake keys; live NVIDIA/Gemini calls and on-device UI checks require your own credentials/device and are not claimed as tested.

Manual checks: add keys, send a group message, switch modes, mute either AI, test offline errors, clear during typing, and reopen to verify saved history. To test model fallback, temporarily set a nonexistent NVIDIA primary model and an active fallback model, then restore the defaults.

The source-built Gradle wrapper and its Apache-2.0 license/provenance remain included under `gradle/`. See `gradle/WRAPPER-NOTICE.md` for reproduction details.
