# AI Chatroom — configurable agents

Android 8+ group chat built with Kotlin, Jetpack Compose, Room, Retrofit and Android Keystore. Add any number of enabled agents, including multiple agents using the same provider. No OpenAI credits are required when using other providers.

## Set up a room

1. Open **Settings → Add agent** and select a provider preset.
2. Set the display name, avatar letter/emoji and RGB color. Review the API base URL, model and authentication style.
3. Enter that provider's API key and save. Blank keeps an existing key; **Remove saved key** deletes it. Changing the base URL clears the previous key unless you enter a replacement.
4. Enable the agents you want, choose Friendly or Expert mode and send a message. Each enabled agent replies once and sees the preceding saved replies.
5. **Stop** cancels the current turn. **Clear** cancels and joins active work before deleting local history.

Removing an agent archives its profile and removes its credential, keeping its past messages readable. Renaming/recoloring updates its identity in old bubbles. USER is fixed. API secrets and the GitHub PAT use the existing AES-GCM Android Keystore vault; Room stores only profile configuration and messages. Room itself is not encrypted. App backups and screenshots remain disabled.

## Provider presets and current terms

Official documentation checked **2026-09-21**. These are editable starting points, not availability or free-use guarantees. Quotas, model IDs, geographic eligibility, data-use terms and account billing state must be checked again before shipping. Enabling multiple agents multiplies requests. The app never automatically switches to a paid provider.

| Preset | API base | Default model | Free-use caveat / official source |
| --- | --- | --- | --- |
| Groq | `https://api.groq.com/openai/v1/` | `openai/gpt-oss-20b` | Recurring free plan with model-dependent limits; check your [account limits](https://console.groq.com/docs/rate-limits). |
| OpenRouter Free | `https://openrouter.ai/api/v1/` | `openrouter/free` | [Free router](https://openrouter.ai/openrouter/free) selects zero-priced models; availability and [account caps](https://openrouter.ai/docs/api-reference/limits) vary. Exact cap figures did not render in the fetched limits table, so they are not asserted here. |
| Google AI Studio | `https://generativelanguage.googleapis.com/` | `gemini-3.5-flash-lite` | [Pricing](https://ai.google.dev/gemini-api/docs/pricing) lists free-tier text usage; account/region quotas apply and free-tier data may be used to improve products. |
| Local Ollama | `http://10.0.2.2:11434/v1/` | `llama3.2:3b` | No hosted API charge or key; requires your own hardware and downloaded model. [Compatible API](https://docs.ollama.com/api/openai-compatibility). |
| Cerebras — trial only | `https://api.cerebras.ai/v1/` | `gpt-oss-120b` | **Not a recurring free tier.** [Current terms](https://inference-docs.cerebras.ai/support/rate-limits): verified payment method, $5 trial expiring in 30 days. [Model catalog](https://inference-docs.cerebras.ai/models/overview). |
| Hugging Face — tiny credit | `https://router.huggingface.co/v1/` | `Qwen/Qwen2.5-Coder-32B-Instruct` | [Free-user credit](https://huggingface.co/docs/inference-providers/pricing) is $0.10/month, subject to change: not meaningful sustained multi-agent usage. Model is listed in [chat documentation](https://huggingface.co/docs/inference-providers/tasks/chat-completion); verify a live serving provider. |
| NVIDIA — legacy preset | `https://integrate.api.nvidia.com/v1/` | `nvidia/nemotron-3-super-120b-a12b` | Existing settings/history preserved. Check [NVIDIA Build](https://build.nvidia.com/) for quotas and active model IDs; the inherited Qwen fallback may be unavailable. |

Cerebras and Hugging Face are intentionally labeled as trial/limited-credit choices, not marketed as sustained free chat. Provider docs were reviewed; live credentialed inference was not tested.

### Local Ollama

On your computer, install Ollama and pull the selected model (`ollama pull llama3.2:3b`). Start Ollama, select the local preset and set a context budget matching the server configuration. The emulator uses `10.0.2.2`; a physical phone needs the computer's private LAN IP and a reachable Ollama listener. Avoid public port exposure. Hardware/model licenses and electricity costs still apply.

Android cleartext is enabled to support arbitrary private LAN IPs. Application validation permits HTTP **only** for explicit local IP/localhost configurations with `AuthStyle.NONE` and **Allow keyless local HTTP** enabled. Cloud endpoints require HTTPS. Redirect following is disabled so credential headers cannot be forwarded to another host. Local HTTP chat traffic is unencrypted.

## Generic provider configuration

`AgentProfile(id, displayName, color, avatarLetter, providerConfig)` lives in the Room `agents` table. Stable IDs also isolate encrypted key slots. `ProviderConfig` supports:

- API base root (include the provider's `/v1/` or `/api/v1/` prefix).
- Bearer, `x-goog-api-key`, custom header name or no authentication.
- OpenAI-compatible `/chat/completions` or Gemini `generateContent` request shape.
- Model ID, optional max output tokens, context budget and fallback model.
- Compatible `chat_template_kwargs.enable_thinking` or Gemini `thinkingLevel` overrides. Only enable fields supported by your chosen model; some compatible APIs reject extra fields.

`OpenAiCompatibleParticipant` is the **single** implementation for compatible providers. Gemini has a separate adapter for its genuinely different JSON format. Quick mode retains known legacy model tuning, uses 2,048 output tokens for Friendly / 4,096 for Expert, and 8,192 when disabled; explicit agent max-tokens overrides these defaults.

The 90-second deadline covers provider calls, retries, model fallback, and optional GitHub read/follow-up work. 429/5xx/network errors get at most three attempts with bounded Retry-After. Only 404/410 switch to a distinct configured fallback, once. Cancellation always propagates. This is sequential complete-response delivery, **not token streaming**; each agent shows queued/responding/done status while the turn is running.

## Storage migration

Room schema version **1 → 2** rebuilds `messages` with `agentId` instead of `speaker`, preserves message IDs/text/error flags/timestamps, and seeds USER/NVIDIA/GEMINI/CHATGPT profiles with identical stable IDs. Historical ChatGPT remains archived with its original label. A one-time settings import preserves NVIDIA/Gemini model choices and enable flags. Existing encrypted credential slot names do not change. No destructive fallback migration is used.

## Markdown and APK size decision

Message bubbles support headings, bold/italic, inline code, flat ordered/unordered lists, pipe tables and fenced code. Tables/code scroll horizontally; each fenced block has **Copy code**, preserving its exact internal whitespace. Lexical highlighting covers common keywords, strings, comments and numbers; unknown languages remain readable.

Evaluated [multiplatform-markdown-renderer](https://github.com/mikepenz/multiplatform-markdown-renderer), which offers fuller Markdown and optional highlighting integrations. Chose an explicit small renderer for this requested subset: **zero additional production dependencies**, no WebView, parser package or highlighting package. This is an APK dependency-footprint decision, not a measured claim that it is a particular number of KB smaller. Robolectric dependencies are test-only. Nested lists, full CommonMark conformance, HTML, links/images, math and language-complete syntax analysis are not supported. Do not rely on the renderer for precise document layout.

## GitHub connection and per-message confirmation

1. Create a fine-grained PAT restricted to the repository you want. Grant Contents read for file reads, Contents write for file creation, and Issues read/write as needed. [GitHub authentication](https://docs.github.com/en/rest/authentication/authenticating-to-the-rest-api).
2. In Settings enter `owner/repository` and PAT, then **Verify & connect**. The app validates the account and repository before saving the encrypted token.
3. Ask an agent to list/read files or issues, or propose creating a file/issue. A deliberately limited JSON action protocol works across compatible and Gemini providers without requiring model-specific native function-calling support.
4. Reads can execute once per agent response, followed by one model answer. Results are untrusted transcript data and may be shared with enabled providers.
5. Writes become proposals attached to the exact saved AI message. Select **Review write** to inspect repository, action, path/title and full body. Only **Confirm this write** executes it. Reject/cancel does not write.

Approvals are bound to a message ID and connected repository, consumed before I/O, and invalidated on disconnect, chat clear or process restart. There are no automatic write retries: an ambiguous network failure tells you to inspect GitHub before retrying. The token is never included in model prompts. APIs are restricted to `api.github.com`; model-supplied URLs or repository overrides are rejected.

Supported actions: list/read/create files and list/read/create issues. Lists are bounded (first 20 issues; GitHub directory limit applies), and read previews are capped. File creation targets the repository's default branch and omits `sha`, so existing files cannot be overwritten. No update/delete tool is exposed. [Contents API contract](https://docs.github.com/en/rest/repos/contents), [Issues API contract](https://docs.github.com/en/rest/issues/issues). Verify current PAT permissions, organization policies and API behavior before production use.

## Context management

Before **each** provider call, `ContextBudget` reserves space for system text, output and wire overhead, then includes the latest human request plus recent messages that fit. It excludes error bubbles and never changes local history. UTF-8 byte counting deliberately overestimates typical BPE tokens, including non-Latin text; it is not an exact provider tokenizer. An oversized latest user message or system/output reservation produces an explicit explanation instead of silently dropping the request. Set each agent's context/output limits to its actual model limits. Earlier facts can be omitted; automatic semantic summarization is not implemented.

## Build and validation

Requires JDK 17, Android SDK 35, Gradle 8.11.1 and Android Gradle Plugin 8.9.2.

```bash
chmod +x gradlew
./gradlew --no-daemon testDebugUnitTest assembleDebug
```

Windows: `.\gradlew.bat testDebugUnitTest assembleDebug`.
Output: `app/build/outputs/apk/debug/app-debug.apk`.

The suite has **45 tests: the original 21 scenarios plus 24 new tests**. The existing **21 test scenarios are retained**, adapting enum references to profiles and routing old NVIDIA test fixtures through the generic implementation. New tests cover the changes below. Validation status is recorded in the PR; a test existing in source is not evidence that it passed. CI on the feature branch builds/tests without publishing a release. Main/master release builds run only after tests and APK signature verification.

| Step | Existing tests touched | New tests |
| --- | --- | --- |
| 1. Runtime agents/migration | Profile type references in all four original test files; historical identity assertion retained | `AgentMigrationTest`: real SQLite v1→v2 open/Room schema validation, all four legacy speakers, rename/archive history, custom profile round-trip, fixed USER |
| 2. Generic providers | `ProviderTest`, `NvidiaFallbackTest`, `ReplyTuningTest`: generic DTO/API and compatible base prefix; old assertions retained | `AgentFeaturesTest`: custom base path/header, self-role, max-token override, authentication styles, URL/cleartext restrictions, malformed URLs and credential-safe diagnostics |
| 3. Presets | None beyond profile adaptation | Preset configuration validation and explicit Cerebras trial labeling |
| 4. Arbitrary roster | `TurnEngineTest`: same six ordering/error/cancellation/deadline scenarios using profiles | Three-agent turn/peer visibility, full-roster and solo prompts |
| 5. Rich content | None | `MarkdownTest`: required blocks, exact code whitespace, unfinished fences, emphasis/escapes, escaped table pipes, non-mutating highlighting |
| 6. Settings/theme/status | None | Light/dark avatar contrast; UI/device checklist below remains manual |
| 7. GitHub | None | `GithubCapabilityTest`: explicit protocol parsing, path restrictions, reject/clear, per-message/repository approval binding, no replay, no write through read path, exact REST body/header, create-only file payload without SHA, decoded file reads and bounded issue listing |
| 8. Context | Existing provider contract assertions retained | Latest human retention, original-history preservation, Unicode byte cost, oversized input, system/output reservation, error exclusion |

Manual device checks before release: upgrade a populated v1 install; add/rename/recolor/remove agents; restart to check persistence; verify dark/light layout and table scrolling; copy code; test custom/cloud/Ollama keys; stop/clear during a response; verify GitHub reject/confirm/double tap/disconnect/process-death cases on a disposable repository. Android Keystore hardware behavior and Compose interactions require device validation; JVM tests do not replace it.

Debug signing keys generated on different runners may not allow updating an existing installation. Preserve important history before uninstalling. A stable production signing/update path is not configured.

## Code map and comments

KDoc and inline comments explain the purpose, data flow and safety/cancellation decisions in changed code rather than repeating every Kotlin statement.

- `domain/AgentProfile.kt`: identities, configuration, endpoint validation and legacy seeds.
- `domain/Chat.kt`, `TurnEngine.kt`: messages, active-roster prompts and bounded sequential turns.
- `domain/ProviderPresets.kt`, `ContextBudget.kt`: preset data and conservative request-size limits.
- `data/ChatDatabase.kt`: profile/message persistence and non-destructive migration.
- `data/SettingsStore.kt`: shared Keystore vault, legacy settings import and GitHub connection settings.
- `network/Providers.kt`: compatible/Gemini adapters, transcripts, retries and fallback.
- `network/GithubCapability.kt`: constrained REST capability and one-use confirmation gate.
- `ui/ChatViewModel.kt`: lifecycle, profile edits, state and pending write proposals.
- `ui/ChatroomApp.kt`, `MarkdownContent.kt`, `Theme.kt`: settings, message rendering and contrast.

Source paths are under `app/src/main/java/com/example/aichatroom`. Gradle wrapper provenance/license remain in `gradle/`.
