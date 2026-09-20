# AI Chatroom

A native Kotlin / Jetpack Compose Android app where a human, ChatGPT and Gemini share one persistent conversation. Material 3, system dark mode, MVVM, Retrofit, Room, coroutines and Flow.

## Open and build

1. Extract this ZIP and open the **AIChatroom** folder in Android Studio.
2. Use Android Studio Meerkat (2024.3.1) or a newer version compatible with Android Gradle Plugin 8.9.2. Select **JDK 17** as the Gradle JDK.
3. Install **Android SDK Platform 35** in SDK Manager. Let Gradle sync and download dependencies. Android Studio creates `local.properties` with your SDK path.
4. Select an emulator or device running **Android 8.0 / API 26 or later**.
5. Run the `app` configuration. Enter API keys inside the app's Settings screen.

Command line, from the extracted project directory:

```bash
# Linux / macOS
chmod +x gradlew
./gradlew testDebugUnitTest assembleDebug
```

```powershell
# Windows PowerShell
.\gradlew.bat testDebugUnitTest assembleDebug
```

For command-line builds without opening Android Studio, configure `ANDROID_HOME` to your Android SDK installation, or create a `local.properties` containing `sdk.dir=/absolute/path/to/Android/Sdk`.

APK output: `app/build/outputs/apk/debug/app-debug.apk`.

Install with `adb install -r app/build/outputs/apk/debug/app-debug.apk`, or copy the APK to the phone and open it. This project includes source and a Gradle wrapper; **a compiled APK is not included**.

## API keys: no source-code changes needed

- Open **Settings** using the gear icon.
- Paste your OpenAI API key and Gemini API key; tap **Save settings**.
- Leave a field blank to retain its existing key. The Remove button deletes a saved key.
- Keys are not displayed again. To replace one, paste its replacement.
- Create/manage OpenAI keys at https://platform.openai.com/api-keys and Gemini keys at https://aistudio.google.com/apikey.
- Each enabled provider needs its own usable API key and model access/quota. An API subscription/credit is separate from a consumer chat-app subscription.
- Defaults are `gpt-4.1-mini` and `gemini-2.5-flash`; change model IDs in Settings if your account uses other supported models. OpenAI models must support Chat Completions and the configured parameters.
- Never paste real keys into source, Gradle files, tests or GitHub Actions secrets for building. Build/tests do not require live keys.

## Use the room

1. Choose **Friendly Roast** or **Expert Mode**.
2. Tap each participant chip to enable/mute them. A single enabled participant gives solo chat. Sending is disabled if both are muted.
3. Send a message. It is saved locally immediately.
4. The first enabled AI replies, then the next AI sees that reply before responding.
5. ChatGPT normally leads; approximately 25% of turns reverse the order. Each AI speaks at most once per human message.
6. Tap the trash icon and confirm to cancel an active turn and delete local history. Keys and preferences remain.

Friendly Roast allows light teasing while avoiding cruel or sensitive personal attacks. Expert Mode removes jokes and emphasizes detailed technical discussion. Changing mode affects future turns; existing history remains available.

## Structure

| Path | Responsibility |
| --- | --- |
| `app/build.gradle.kts` | Android app configuration and pinned dependencies |
| `app/src/main/AndroidManifest.xml` | Internet permission, application/activity and backup restrictions |
| `.../ChatApplication.kt` | App-scoped dependencies / manual dependency injection |
| `.../MainActivity.kt` | Compose entry point and ViewModel factory |
| `.../domain/Chat.kt` | Message models, provider/store interfaces and mode prompts |
| `.../domain/TurnEngine.kt` | Sequential AI orchestration with cancellation and occasional reversed order |
| `.../data/ChatDatabase.kt` | Room entities, DAO and repository |
| `.../data/SettingsStore.kt` | Android Keystore AES-GCM credential encryption and preferences |
| `.../network/Providers.kt` | Retrofit APIs/DTOs, named transcript mapping, retries and friendly errors |
| `.../ui/ChatViewModel.kt` | UI state, turn lifecycle, setting updates and reset coordination |
| `.../ui/ChatroomApp.kt` | Chat/settings screens, avatars, typing indicators, composer and confirmation |
| `.../ui/Theme.kt` | Material 3 light/dark palettes |
| `app/src/test/.../TurnEngineTest.kt` | Ordering, peer awareness, solo mode, failure and cancellation tests |
| `app/src/test/.../ProviderTest.kt` | MockWebServer API-contract and retry tests |
| `gradle/wrapper/` | Wrapper launcher JAR and Gradle distribution configuration |
| `.github/workflows/android.yml` | Optional GitHub build/test and debug-APK artifact workflow |

`...` in main paths means `app/src/main/java/com/example/aichatroom`.

## How shared context works

The ViewModel admits only one user turn at a time and snapshots its mode and enabled providers. The repository saves the human message before calling `TurnEngine`.

For each provider, the engine fetches all messages ordered by their Room ID, excluding error bubbles. It sends that history with the provider-specific system prompt, waits for the reply, then saves it before invoking the next provider. Thus, the second response sees the first response on that same turn. A failure by one provider becomes a persisted diagnostic bubble and does not prevent the other provider from answering.

OpenAI receives its own previous messages as `assistant`; human and Gemini messages are separate named `user` entries. Gemini receives its own messages as `model` and other participants as `user`, with JSON-encoded speaker labels. Consecutive Gemini API roles are grouped. There is no synthetic impersonation of the other AI.

Room Flow publishes new messages as they are persisted; StateFlow publishes busy/typing states. **UI updates arrive per complete AI reply, not token by token.** The REST calls use Chat Completions and `generateContent`, not SSE. This keeps a partial network response out of persistent history.

## Errors and retries

- Missing key: inline prompt linking to Settings, with no network request for that participant.
- Invalid key / permission denial: inline error; not automatically retried.
- HTTP 429, HTTP 5xx and I/O failures: at most 3 total attempts, with backoff and support for `Retry-After` seconds or HTTP-date.
- Long Retry-After (>30 seconds): surface the error so the app does not retry before the requested cooldown.
- Empty/blocked response: inline explanation without crashing.
- HTTP 400/413: explanation to check the selected model or reset an overlong conversation.
- Cancellation propagates through Retrofit and coroutines; it is not treated as a provider failure.
- Clear chat cancels and joins the running turn before deleting messages, preventing late replies from repopulating the room.
- Raw provider responses, exception messages, credentials and HTTP headers are not logged or shown.
- Automatic retries can repeat a request accepted by the server if its response was lost; additional API usage is possible.

After a terminal error, correct the key/quota/network issue and send your message again. There is no automatic unbounded background retry.

## Persistence and security

Keys are encrypted with AES/GCM using a non-exportable Android Keystore key. Only ciphertext and the IV are saved in private SharedPreferences. Credential fields use in-memory Compose state, not saved-state bundles. Backups/device transfer are excluded, screenshots are blocked, redirects are disabled, and API keys are carried in HTTPS headers rather than URL query strings.

The Room database is private app storage, **not separately encrypted**. It stores the full local conversation and diagnostic bubbles. This app sends the entire non-error conversation to each enabled provider; muting a provider stops future requests to it, but does not erase content already sent. Clear chat removes the local copy, not provider-side records. No analytics or backend is included.

This is a bring-your-own-key client. Do not ship a shared developer API key in a public APK. For a commercial deployment using developer-funded keys, add a backend proxy and user authentication.

## Test checklist

Automated tests (no real keys, no provider charges):

```bash
./gradlew testDebugUnitTest
```

The 11 tests verify:

- Both reply orders and same-turn peer visibility.
- Solo mode and continuation after a provider failure.
- Cancellation produces no late reply or error bubble.
- OpenAI endpoint, authorization and peer role/name mapping.
- Gemini endpoint, API-key header, system instruction and peer transcript.
- Gemini thought parts excluded from displayed replies.
- Missing key makes no HTTP request.
- 429 retry recovery, 401 no-retry, bounded I/O retries and cancellation propagation.

Manual device checks:

1. Fresh installation: send without keys; inline errors appear, Settings opens, UI stays usable.
2. Save valid keys and send “Explain Kotlin coroutines, then challenge each other's explanation.” Check the second AI builds on the first.
3. Toggle Expert Mode; send a technical question and check professional tone.
4. Mute each provider in turn; only the active provider responds. Mute both; sending is disabled.
5. Toggle airplane mode; send and wait for bounded retries and an inline error. Restore network and resend.
6. Clear while a typing indicator is visible; wait 15 seconds and verify the chat stays empty.
7. Rotate during a request; check no duplicate user message or duplicate provider call.
8. Close/reopen after replies; history and settings survive. OS process death cancels in-flight work; committed messages remain, but a partial turn is not automatically resumed.
9. Test system light/dark mode, 200% font scale, a small phone and an open keyboard.
10. Replace/remove each key and confirm other preferences/history remain.

## Validation status of this delivery

Source/configuration was reviewed; XML and ZIP integrity were checked. The bundled source-built wrapper launcher was compiled with Java and started successfully, reaching the Gradle download step. Download was blocked by this environment's network restrictions. No Android SDK is installed here, so **Android compilation, the 11 unit tests, emulator checks and live provider calls have not been executed**. Run the commands above or the included GitHub workflow to complete validation.

## Wrapper provenance

The wrapper JAR is compiled from the Gradle `v8.11.1` Apache-2.0 sources because this environment could retrieve source but not binary downloads. Its source is included in `gradle/wrapper-source/`. Only the `NonNullApi` annotation/import in `WrapperDistributionUrlConverter.java` was removed to avoid a compile-time-only annotation dependency. This JAR is not byte-identical to Gradle's prebuilt wrapper and will have a different checksum. The downloaded Gradle 8.11.1 distribution is verified against Gradle's official SHA-256 in `gradle-wrapper.properties`.

To replace the source-built launcher with the official Gradle-generated wrapper after the first successful sync:

```bash
./gradlew wrapper --gradle-version 8.11.1 --distribution-type bin
```

See `gradle/GRADLE-LICENSE` and `gradle/WRAPPER-NOTICE.md`.

## References

- [OpenAI Chat API](https://developers.openai.com/api/reference/resources/chat)
- [Gemini generateContent API](https://ai.google.dev/api/generate-content)
- [Android Gradle Plugin 8.9 compatibility](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
- [Official Gradle distribution checksums](https://gradle.org/release-checksums/)
