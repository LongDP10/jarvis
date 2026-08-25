# JARVIS Android AI Assistant — Design

Date: 2026-08-23
Status: Approved
Target device: Samsung Galaxy S24 Ultra (Android 14/15, One UI)

## 1. Purpose

A voice-first personal AI assistant for Android that listens, understands
natural language in Vietnamese and English, executes real actions on the
phone, speaks its answers, and stays visible as a floating orb above other
apps.

The product test is behavioural, not visual: the user says "Hey Jarvis, open
YouTube and search for SCADA IEC 104 tutorial" and the phone actually does
it, narrating progress on the orb as it goes.

## 2. Non-goals

- Not a chatbot with a nice skin. The chat screen is secondary to voice.
- No root, no ADB, no faking capabilities Android denies.
- No cloud microphone streaming unless the user explicitly opts in.

## 3. Platform baseline

| Item | Value |
|---|---|
| applicationId | com.jarvis.assistant |
| minSdk | 30 |
| targetSdk / compileSdk | 35 |
| Gradle / AGP / Kotlin | 8.13 / 8.7.3 / 2.0.21 |
| UI | Jetpack Compose, Material 3, dark-first, edge-to-edge |
| DI | Hilt |
| Concurrency | Coroutines + StateFlow |
| Persistence | Room (history, command log), DataStore (settings), EncryptedSharedPreferences (API keys) |
| Network | OkHttp + kotlinx.serialization |
| Gradle modules | single :app module, separated by package |

minSdk 30 is the floor at which `AccessibilityService.takeScreenshot()` and
`Settings.Panel` exist, both of which the tool layer depends on.

## 4. Architecture

Single module, layered by package. MVVM at the UI edge, plain
service/registry core underneath.

```
ui/             Compose screens + ViewModels
overlay/        Foreground service, WindowManager host, orb rendering
voice/          STT, TTS, VAD, wake word
ai/             AIProvider abstraction, Gemini, OpenAI, agent loop, local matcher
commands/       Tool registry, tool implementations, executor, confirmation gate
accessibility/  AccessibilityService + controller
data/           Room, DataStore, secure storage, repositories
core/           State machine, domain models, result types
utils/          Permissions, app resolver, language detection
```

### 4.1 State machine

`JarvisStateMachine` is an application-scoped singleton exposing
`StateFlow<JarvisState>`. It is the single source of truth shared by the
Activity UI and the overlay service, so the in-app orb and the floating orb
can never disagree.

States: IDLE, WAKE, LISTENING, PROCESSING, EXECUTING, SPEAKING, ERROR,
CANCELLED, CONFIRMATION_REQUIRED, WAITING_FOR_USER.

Each state carries the payload the UI needs: partial transcript, current
step label ("Opening YouTube..."), step index and total, error message.

## 5. Command pipeline (hybrid)

```
audio/text
   |
   v
LocalIntentMatcher  --match-->  ToolRegistry -> executor
   |
   | no match
   v
AIProvider agent loop  <--tool results--  executor
   |
   v
spoken response
```

### 5.1 LocalIntentMatcher

Roughly 40 regex/keyword patterns per language covering the offline command
set: open app, home, back, recents, volume up/down/set/mute, flashlight,
screenshot, play/pause/next/previous, open settings screens, scroll.

Rationale: these are the most frequent commands, they must work with no
network and no token cost, and latency matters more than flexibility. This
is what makes offline mode real rather than aspirational.

### 5.2 AI agent loop

Everything else goes to the configured `AIProvider`. The request carries:

- a language-specific system prompt describing the JARVIS persona and its
  real constraints
- conversation history for the current session
- optional screen context: visible text from the accessibility node tree,
  included when the user's phrasing is deictic ("the first result", "that
  button") or when an earlier tool in the same turn changed the screen
- the tool schemas the user has actually granted permission for

The provider returns prose or tool calls. Tool calls run through the
executor and their results are fed back. The loop is bounded at 6 iterations
to prevent runaway cost, then the model is forced to answer.

This loop is what makes multi-step and context-dependent commands work:
"open the first video" is resolved by the model reading the screen dump and
choosing a node, not by hard-coded heuristics.

### 5.3 AIProvider abstraction

```kotlin
interface AIProvider {
    val id: ProviderId
    suspend fun chat(request: AIRequest): AIResponse   // may contain tool calls
    suspend fun isAvailable(): Boolean
}
```

Implementations: `GeminiProvider` (default), `OpenAIProvider`,
`OllamaProvider` (local LLM over LAN, same interface).

## 6. Tool layer

Every tool declares a name, a JSON schema, the Android permissions it needs,
an `isDangerous` flag, and an implementation returning `ToolResult`
(success / failure / requires-permission / not-supported-by-android).

### 6.1 Capability honesty

Tools must never report success for something Android did not do.

Fully supported via public API: open_app, search_web, open_settings,
open_camera, open_gallery, go_home, go_back, open_recents,
open_notifications, open_quick_settings, take_screenshot, set_volume,
increase_volume, decrease_volume, mute, play_media, pause_media, next_track,
previous_track, toggle_flashlight, tap, swipe, scroll, click_text,
input_text, read_screen, get_location, read_notifications.

Supported but needing a special grant: set_brightness (WRITE_SETTINGS),
set_dnd (notification policy access).

Dangerous, always gated behind explicit confirmation UI: make_call, send_sms.

Restricted by the OS, degraded honestly:

| Tool | OS restriction | Fallback |
|---|---|---|
| toggle_wifi | blocked since Android 10 | open `Settings.Panel.ACTION_INTERNET_CONNECTIVITY` and say JARVIS cannot flip it directly |
| toggle_bluetooth | blocked since Android 13 | system `ACTION_REQUEST_ENABLE` dialog |
| close_app | no public kill API | go home, optionally open recents; reported as a limitation |

### 6.2 Confirmation gate

`ConfirmationGate` intercepts any tool with `isDangerous = true`, moves the
state machine to CONFIRMATION_REQUIRED, and suspends until the user accepts
or rejects. The AI cannot bypass it.

## 7. Voice engine

- **STT**: `SpeechRecognizer` with locale vi-VN / en-US / auto. Auto mode
  scores the transcript for Vietnamese diacritics and vocabulary to pick the
  reply language. `EXTRA_PREFER_OFFLINE` is set when the user selects Local
  voice processing.
- **Wake word**: a `WakeWordEngine` interface; the default implementation
  loops `SpeechRecognizer` inside the foreground service and fuzzy-matches
  "hey jarvis" / "jarvis" plus Vietnamese pronunciations. Debounced and
  self-restarting. Default OFF with an explicit battery warning, because
  continuous recognition is power-hungry. A Porcupine adapter can be added
  later behind the same interface.
- **TTS**: system `TextToSpeech`; enumerate installed vi/en voices so the
  user can pick male or female; rate and pitch sliders.
- **VAD**: RMS threshold plus `onEndOfSpeech`, auto-stop after ~1.2s silence.

## 8. Overlay

A foreground service holds a `WindowManager` view of type
`TYPE_APPLICATION_OVERLAY`. Because `ComposeView` requires lifecycle,
saved-state and viewmodel-store owners that a Service does not provide, a
`ComposeOverlayHost` implements all three. This is the most common crash
source in Compose overlays and is handled from the start.

The orb is drawn with Compose `Canvas`: counter-rotating arcs, a
radial-gradient glow, subtle particles. Animation is driven by real signals —
microphone RMS while listening, TTS utterance progress while speaking, a
sweep while thinking, a progress ring over completed tool steps while
executing.

Gestures: tap to listen, double tap to wake, long press to open chat, drag
to move with snap to the nearest corner.

## 9. Data, security, i18n

- API keys live in `EncryptedSharedPreferences` (AES256-GCM, keystore
  backed). Never hard-coded, never logged, never sent anywhere but the
  provider's own endpoint.
- `PermissionManager` requests each permission when the feature needs it,
  with an onboarding screen explaining why.
- Room stores conversations and a command log; the command log powers both
  the History screen and the Debug Console.
- Strings in `values/strings.xml` and `values-vi/strings.xml`. The AI system
  prompt switches language too.

## 10. Error handling

Failures are reported, never masked. Distinct messages for: not understood,
missing permission, action not supported by Android, no network for a
cloud-only command, provider error, and no API key configured.

## 11. Verification

The build is verified by compiling with `gradlew assembleDebug`. Runtime
behaviour on hardware cannot be verified from this workstation — no device or
emulator is attached — so on-device behaviour is validated by the user and
iterated on.

## 12. Implementation phases

1. Gradle project, manifest, theme, Hilt, navigation shell
2. Core: state machine, models, DataStore settings, secure storage, Room
3. Voice: STT, TTS, VAD, wake word
4. AI: provider abstraction, Gemini, OpenAI, agent loop, local matcher
5. Tools: registry, ~28 tools, executor, confirmation gate, app registry
6. Accessibility service and controller
7. Overlay service and orb
8. UI: Home, Chat, Settings, History, Debug Console, Onboarding, i18n
