# JARVIS Android Assistant Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A voice-first Android assistant that hears a spoken command in Vietnamese or English, plans it into tool calls, executes real actions on the phone, narrates progress on a floating orb, and speaks the result.

**Architecture:** Single `:app` Gradle module layered by package. An application-scoped `JarvisStateMachine` is the one source of truth shared by the Activity and the overlay service. Commands take a hybrid path: an offline regex matcher handles the ~40 most common commands with no network, everything else goes to a bounded LLM agent loop that calls typed tools. Tools never fake success — where Android forbids an action they degrade to a Settings panel and say so.

**Tech Stack:** Kotlin 2.0.21, AGP 8.7.3, Gradle 8.13, Jetpack Compose + Material 3, Hilt, Coroutines/StateFlow, Room, DataStore, EncryptedSharedPreferences, OkHttp, kotlinx.serialization, AccessibilityService, WindowManager overlay.

**Spec:** `docs/superpowers/specs/2026-08-23-jarvis-android-assistant-design.md`

## Global Constraints

- applicationId `com.jarvis.assistant`; minSdk 30; targetSdk and compileSdk 35.
- Gradle 8.13, AGP 8.7.3, Kotlin 2.0.21, KSP 2.0.21-1.0.28, JDK 21 (`C:/Program Files/Android/Android Studio1/jbr`).
- Single Gradle module `:app`. No new modules.
- Every user-visible string goes in `values/strings.xml` and `values-vi/strings.xml`. No hard-coded UI text in Kotlin.
- API keys only ever live in `EncryptedSharedPreferences`. Never hard-coded, never written to logs, never sent anywhere but the provider endpoint.
- No tool may return `ToolResult.Success` for an action Android did not perform. Use `ToolResult.NotSupported` with an explanation and, where one exists, a fallback Intent.
- Any tool with `isDangerous = true` must pass through `ConfirmationGate`. There is no bypass path for the AI.
- All blocking work runs on Dispatchers.IO or Default. Nothing blocks the main thread.

## Verification Strategy

Two levels, both real:

1. **JVM unit tests** (`app/src/test/`) for the logic that is genuinely testable off-device: `LocalIntentMatcher`, `LanguageDetector`, `AppRegistry` name resolution, provider JSON request/response mapping, `ToolRegistry` schema generation. These follow the write-test-first cycle.
2. **Compilation** via `gradlew assembleDebug` after each phase. For Android framework code (Services, Accessibility, Compose overlay) that cannot run on the JVM, a clean compile plus manual on-device testing by the user is the honest verification bar — there is no device or emulator attached to this workstation.

Do not claim a phase works on hardware. Claim it compiles, and say which parts need on-device confirmation.

---

## File Structure

```
app/src/main/java/com/jarvis/assistant/
  JarvisApplication.kt              Hilt entry point, TTS/STT warmup
  MainActivity.kt                   single activity, edge-to-edge, nav host

  core/
    JarvisState.kt                  sealed state + payloads
    JarvisStateMachine.kt           singleton StateFlow, transition guards
    Language.kt                     VI / EN / AUTO enum + resolution
    Result.kt                       JarvisResult<T> success/failure wrapper

  data/
    settings/SettingsRepository.kt  DataStore-backed, exposes SettingsFlow
    settings/JarvisSettings.kt      immutable settings data class
    secure/SecureKeyStore.kt        EncryptedSharedPreferences wrapper
    db/JarvisDatabase.kt            Room database
    db/ConversationDao.kt           + MessageEntity, ConversationEntity
    db/CommandLogDao.kt             + CommandLogEntity (History + Debug)
    repo/ConversationRepository.kt
    repo/CommandLogRepository.kt

  voice/
    SpeechRecognitionManager.kt     STT, RMS flow, partial results
    TextToSpeechManager.kt          TTS, voice enumeration, progress flow
    VoiceActivityDetector.kt        RMS + silence timeout
    WakeWordEngine.kt               interface
    SpeechWakeWordEngine.kt         default looping implementation
    LanguageDetector.kt             Vietnamese scoring

  ai/
    AIProvider.kt                   interface + AIRequest/AIResponse/ToolCall
    ProviderId.kt
    GeminiProvider.kt
    OpenAIProvider.kt
    OllamaProvider.kt
    AIProviderFactory.kt            picks provider from settings
    SystemPrompts.kt                vi + en persona and constraints
    AgentLoop.kt                    bounded tool-calling loop
    LocalIntentMatcher.kt           offline regex fast path

  commands/
    Tool.kt                         Tool interface, ToolSpec, ToolParam
    ToolResult.kt                   sealed result
    ToolRegistry.kt                 registration + JSON schema export
    CommandExecutor.kt              runs a tool call, logs, updates state
    ConfirmationGate.kt             suspends on dangerous tools
    tools/AppTools.kt               open_app, close_app, search_web
    tools/NavigationTools.kt        home, back, recents, notifications, quick settings
    tools/MediaTools.kt             play/pause/next/prev, volume, mute
    tools/SystemTools.kt            flashlight, brightness, dnd, screenshot, wifi, bluetooth
    tools/UiTools.kt                tap, swipe, scroll, click_text, input_text, read_screen
    tools/CommunicationTools.kt     make_call, send_sms (dangerous)
    tools/InfoTools.kt              get_location, read_notifications, get_time

  accessibility/
    JarvisAccessibilityService.kt   the service itself
    AccessibilityController.kt      singleton bridge, gestures, node search
    ScreenReader.kt                 node tree -> text summary for the LLM

  notifications/
    JarvisNotificationListener.kt   read_notifications source

  overlay/
    OverlayService.kt               foreground service, WindowManager
    ComposeOverlayHost.kt           Lifecycle/SavedState/ViewModelStore owner
    FloatingOrb.kt                  overlay composable + drag/snap
    OrbCanvas.kt                    the shared orb drawing
    OrbAnimation.kt                 state-driven animation values

  ui/
    theme/                          Color.kt, Type.kt, Theme.kt
    nav/JarvisNavHost.kt
    home/HomeScreen.kt + HomeViewModel.kt
    chat/ChatScreen.kt + ChatViewModel.kt
    settings/SettingsScreen.kt + SettingsViewModel.kt
    history/HistoryScreen.kt + HistoryViewModel.kt
    debug/DebugConsoleScreen.kt + DebugViewModel.kt
    onboarding/OnboardingScreen.kt + PermissionCard.kt
    common/ConfirmationDialog.kt

  utils/
    PermissionManager.kt
    AppRegistry.kt                  installed-app index + fuzzy resolve
    NetworkMonitor.kt

  di/                               AppModule, DataModule, VoiceModule, AiModule, ToolModule

app/src/main/res/
  values/strings.xml, values-vi/strings.xml, values/themes.xml
  xml/accessibility_service_config.xml
  drawable/, mipmap-anydpi-v26/

app/src/test/java/com/jarvis/assistant/
  ai/LocalIntentMatcherTest.kt
  voice/LanguageDetectorTest.kt
  utils/AppRegistryTest.kt
  ai/GeminiProviderMappingTest.kt
  ai/OpenAIProviderMappingTest.kt
  commands/ToolRegistryTest.kt
```

---

### Task 1: Buildable Gradle skeleton

**Files:**
- Create: `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `gradle.properties`, `local.properties`, `.gitignore`
- Create: `gradle/wrapper/gradle-wrapper.properties`, `gradle/wrapper/gradle-wrapper.jar` (copy from `C:/Users/Admin/AndroidStudioProjects/Jarvis/gradle/wrapper/gradle-wrapper.jar`), `gradlew`, `gradlew.bat`
- Create: `app/build.gradle.kts`, `app/proguard-rules.pro`, `app/src/main/AndroidManifest.xml`
- Create: `JarvisApplication.kt`, `MainActivity.kt`, `ui/theme/{Color,Type,Theme}.kt`, `res/values/strings.xml`, `res/values-vi/strings.xml`, `res/values/themes.xml`

**Interfaces:**
- Produces: `JarvisApplication` (`@HiltAndroidApp`), `MainActivity` (`@AndroidEntryPoint`), `JarvisTheme { content }` composable, string resource keys.

- [ ] Copy the wrapper jar, pin `distributionUrl` to `gradle-8.13-bin.zip` (already cached in `~/.gradle/wrapper/dists`)
- [ ] Write the version catalog with every dependency the later phases need, so no phase has to touch Gradle again
- [ ] Write the manifest with all permissions, the accessibility service, notification listener, overlay service, and `<queries>` for `AppRegistry`
- [ ] Minimal `MainActivity` showing a themed placeholder so the module compiles
- [ ] Run `gradlew.bat assembleDebug` with `org.gradle.java.home` set to the Studio JBR — expect BUILD SUCCESSFUL

### Task 2: Core state and persistence

**Files:**
- Create: `core/JarvisState.kt`, `core/JarvisStateMachine.kt`, `core/Language.kt`, `core/Result.kt`
- Create: `data/settings/{JarvisSettings,SettingsRepository}.kt`, `data/secure/SecureKeyStore.kt`
- Create: `data/db/{JarvisDatabase,ConversationDao,CommandLogDao,Entities}.kt`, `data/repo/{ConversationRepository,CommandLogRepository}.kt`
- Create: `di/{AppModule,DataModule}.kt`, `utils/NetworkMonitor.kt`

**Interfaces:**
- Produces:
  - `sealed class JarvisState { Idle; Wake; Listening(partial: String, rms: Float); Processing(label: String); Executing(step: String, index: Int, total: Int); Speaking(text: String, progress: Float); ConfirmationRequired(request: ConfirmationRequest); WaitingForUser(question: String); Error(message: String); Cancelled }`
  - `JarvisStateMachine.state: StateFlow<JarvisState>`, `fun transition(to: JarvisState)`, `fun reset()`
  - `SettingsRepository.settings: Flow<JarvisSettings>` plus one suspend setter per field
  - `SecureKeyStore.getApiKey(ProviderId): String?`, `setApiKey(ProviderId, String)`, `clear(ProviderId)`
  - `CommandLogRepository.log(entry: CommandLogEntry)`, `.recent(limit: Int): Flow<List<CommandLogEntry>>`

- [ ] Define state and settings types, then the state machine with logging of every transition
- [ ] Room entities, DAOs, database, KSP-generated; repositories on top
- [ ] `SecureKeyStore` over `EncryptedSharedPreferences` with `MasterKey.Builder(AES256_GCM)`
- [ ] `gradlew.bat assembleDebug` — expect BUILD SUCCESSFUL

### Task 3: Voice engine

**Files:**
- Create: `voice/SpeechRecognitionManager.kt`, `voice/TextToSpeechManager.kt`, `voice/VoiceActivityDetector.kt`, `voice/WakeWordEngine.kt`, `voice/SpeechWakeWordEngine.kt`, `voice/LanguageDetector.kt`, `di/VoiceModule.kt`
- Test: `app/src/test/java/com/jarvis/assistant/voice/LanguageDetectorTest.kt`

**Interfaces:**
- Consumes: `JarvisStateMachine`, `SettingsRepository`, `Language`.
- Produces:
  - `SpeechRecognitionManager.listen(language: Language, preferOffline: Boolean): Flow<SttEvent>` where `SttEvent = Partial(String) | Rms(Float) | Final(String) | Error(SttError)`
  - `TextToSpeechManager.speak(text: String, language: Language): Flow<TtsEvent>`; `availableVoices(Language): List<VoiceOption>`; `stop()`
  - `WakeWordEngine { fun start(onDetected: () -> Unit); fun stop() }`
  - `LanguageDetector.detect(text: String): Language`

- [ ] Write `LanguageDetectorTest` first: Vietnamese diacritics and common words score VI, plain English scores EN, empty defaults to the configured language
- [ ] Run it, watch it fail, implement `LanguageDetector`, watch it pass
- [ ] Wrap `SpeechRecognizer` as a `callbackFlow`, forwarding `onRmsChanged` and partial results; recreate the recognizer on `ERROR_RECOGNIZER_BUSY`
- [ ] Wrap `TextToSpeech` with `UtteranceProgressListener` mapped to a progress flow for orb sync
- [ ] `SpeechWakeWordEngine`: restarting recognition loop, fuzzy match on "hey jarvis"/"jarvis"/Vietnamese pronunciations, debounce so one utterance fires once
- [ ] Run unit tests, then `assembleDebug`

### Task 4: AI layer

**Files:**
- Create: `ai/AIProvider.kt`, `ai/ProviderId.kt`, `ai/GeminiProvider.kt`, `ai/OpenAIProvider.kt`, `ai/OllamaProvider.kt`, `ai/AIProviderFactory.kt`, `ai/SystemPrompts.kt`, `ai/AgentLoop.kt`, `ai/LocalIntentMatcher.kt`, `di/AiModule.kt`
- Test: `ai/LocalIntentMatcherTest.kt`, `ai/GeminiProviderMappingTest.kt`, `ai/OpenAIProviderMappingTest.kt`

**Interfaces:**
- Consumes: `SecureKeyStore`, `SettingsRepository`, `ToolRegistry` (schemas), `CommandExecutor`.
- Produces:
  - `interface AIProvider { val id: ProviderId; suspend fun chat(request: AIRequest): AIResponse; suspend fun isAvailable(): Boolean }`
  - `data class AIRequest(messages, tools, language, screenContext: String?)`
  - `sealed class AIResponse { Text(String); ToolCalls(List<ToolCall>); Error(String) }`
  - `data class ToolCall(id: String, name: String, args: JsonObject)`
  - `AgentLoop.run(userText: String, language: Language): AgentOutcome` — bounded at 6 iterations
  - `LocalIntentMatcher.match(text: String, language: Language): ToolCall?`

- [ ] Write `LocalIntentMatcherTest` first with both languages: "mở YouTube"/"open YouTube" -> open_app{app:YouTube}; "tăng âm lượng"/"volume up" -> increase_volume; "về màn hình chính"/"go home" -> go_home; "bật đèn pin" -> toggle_flashlight; "chụp màn hình" -> take_screenshot; and a sentence that must NOT match so it falls through to the LLM
- [ ] Run, fail, implement the matcher, pass
- [ ] Write provider mapping tests against recorded JSON payloads: a Gemini `functionCall` part maps to `ToolCalls`, an OpenAI `tool_calls` array maps to `ToolCalls`, plain content maps to `Text`, an HTTP error maps to `Error` with the provider message
- [ ] Run, fail, implement both providers, pass
- [ ] `AgentLoop`: local matcher first; on miss, call the provider, execute tool calls, feed results back, stop at 6 rounds or a text answer; attach screen context only for deictic phrasing or after a screen-changing tool
- [ ] Unit tests, then `assembleDebug`

### Task 5: Tools

**Files:**
- Create: `commands/Tool.kt`, `commands/ToolResult.kt`, `commands/ToolRegistry.kt`, `commands/CommandExecutor.kt`, `commands/ConfirmationGate.kt`
- Create: `commands/tools/{AppTools,NavigationTools,MediaTools,SystemTools,UiTools,CommunicationTools,InfoTools}.kt`
- Create: `utils/AppRegistry.kt`, `utils/PermissionManager.kt`, `di/ToolModule.kt`
- Test: `commands/ToolRegistryTest.kt`, `utils/AppRegistryTest.kt`

**Interfaces:**
- Consumes: `AccessibilityController`, `JarvisStateMachine`, `CommandLogRepository`.
- Produces:
  - `interface Tool { val spec: ToolSpec; suspend fun execute(args: JsonObject): ToolResult }`
  - `data class ToolSpec(name, description, params: List<ToolParam>, permissions: List<String>, isDangerous: Boolean, requiresAccessibility: Boolean, requiresNetwork: Boolean)`
  - `sealed class ToolResult { Success(message: String, data: JsonObject?); Failure(message); RequiresPermission(permission, rationale); NotSupported(reason, fallbackIntent: Intent?) }`
  - `ToolRegistry.all(): List<Tool>`, `.get(name): Tool?`, `.schemasFor(granted: Set<String>): JsonArray`
  - `CommandExecutor.execute(call: ToolCall): ToolResult`
  - `ConfirmationGate.request(ConfirmationRequest): Boolean` (suspends)
  - `AppRegistry.resolve(query: String): List<AppEntry>` ranked; `AppEntry(label, packageName, launchIntent)`

- [ ] Write `AppRegistryTest` first over a fake package list: exact label match wins; case- and diacritic-insensitive; "chrome" resolves "Google Chrome"; ambiguity returns more than one entry so the caller can ask
- [ ] Run, fail, implement, pass
- [ ] Write `ToolRegistryTest`: every registered tool has a unique name, a non-empty description, and every dangerous tool declares `isDangerous`; generated schema is valid JSON with the declared params
- [ ] Run, fail, implement registry, pass
- [ ] Implement the seven tool files. Wi-Fi, Bluetooth and close_app must return `NotSupported` with the fallback Intent, never `Success`
- [ ] `ConfirmationGate` suspends via `CompletableDeferred`, driven by `JarvisState.ConfirmationRequired`
- [ ] Unit tests, then `assembleDebug`

### Task 6: Accessibility

**Files:**
- Create: `accessibility/JarvisAccessibilityService.kt`, `accessibility/AccessibilityController.kt`, `accessibility/ScreenReader.kt`
- Create: `notifications/JarvisNotificationListener.kt`
- Create: `res/xml/accessibility_service_config.xml`

**Interfaces:**
- Produces:
  - `AccessibilityController.isConnected: StateFlow<Boolean>`
  - `suspend fun globalAction(action: Int): Boolean`
  - `suspend fun tap(x: Float, y: Float): Boolean`, `swipe(...)`, `scroll(direction, times)`
  - `suspend fun clickByText(text: String): Boolean`, `inputText(text: String): Boolean`
  - `suspend fun takeScreenshotToFile(): File?`
  - `ScreenReader.dump(): ScreenSnapshot` — ordered visible text with node ids for the LLM

- [ ] Service registers itself with the controller on connect and clears on disconnect, so tools can tell "not enabled" from "failed"
- [ ] `dispatchGesture` wrapped in `suspendCancellableCoroutine` with a completion callback
- [ ] `clickByText` walks the node tree, matches case- and diacritic-insensitively, climbs to the nearest clickable ancestor
- [ ] `ScreenReader.dump()` produces a compact numbered list, capped in length so it does not blow the token budget
- [ ] `assembleDebug`

### Task 7: Overlay orb

**Files:**
- Create: `overlay/OverlayService.kt`, `overlay/ComposeOverlayHost.kt`, `overlay/FloatingOrb.kt`, `overlay/OrbCanvas.kt`, `overlay/OrbAnimation.kt`

**Interfaces:**
- Consumes: `JarvisStateMachine`, `SettingsRepository`.
- Produces: `OrbCanvas(state: JarvisState, size: Dp, amplitude: Float)` reused by both the home screen and the overlay; `OverlayService.start(context)` / `stop(context)`.

- [ ] `ComposeOverlayHost` implements `LifecycleOwner`, `SavedStateRegistryOwner`, `ViewModelStoreOwner` and attaches them via `setViewTreeLifecycleOwner` and friends — without this the overlay crashes on first composition
- [ ] Foreground service with a `specialUse`/`microphone` type notification, started from settings toggle
- [ ] Window params `TYPE_APPLICATION_OVERLAY | FLAG_NOT_FOCUSABLE | FLAG_LAYOUT_NO_LIMITS`, updated on drag, snapping to the nearest corner on release
- [ ] Orb canvas: counter-rotating arcs, radial glow, amplitude-reactive ring; a status label under it during Executing and Speaking
- [ ] Gestures: tap listens, double tap wakes, long press opens chat via activity intent
- [ ] `assembleDebug`

### Task 8: Screens and wiring

**Files:**
- Create: `ui/nav/JarvisNavHost.kt`, `ui/home/{HomeScreen,HomeViewModel}.kt`, `ui/chat/{ChatScreen,ChatViewModel}.kt`, `ui/settings/{SettingsScreen,SettingsViewModel}.kt`, `ui/history/{HistoryScreen,HistoryViewModel}.kt`, `ui/debug/{DebugConsoleScreen,DebugViewModel}.kt`, `ui/onboarding/{OnboardingScreen,PermissionCard}.kt`, `ui/common/ConfirmationDialog.kt`
- Modify: `MainActivity.kt` to host the nav graph and the confirmation dialog
- Create: `README.md` with setup, permissions, and the honest capability table

**Interfaces:**
- Consumes: everything above.

- [ ] Home: centred orb, state caption, Voice / Commands / History / Settings shortcuts
- [ ] Chat: text entry that goes through the same `AgentLoop` as voice, message list from Room
- [ ] Settings: language, voice + rate + pitch, provider + API key entry, wake word toggle with battery warning, overlay toggle, always-listening toggle, orb size and corner, voice processing mode, debug log toggle
- [ ] History from `ConversationRepository`; Debug Console from `CommandLogRepository` showing the VOICE / AI / TOOL / RESULT / TTS trace
- [ ] Onboarding lists each permission with its reason and a button that opens the right system screen
- [ ] `ConfirmationDialog` bound to `JarvisState.ConfirmationRequired`
- [ ] Fill both `strings.xml` files completely; no literal UI text left in Kotlin
- [ ] Full `gradlew.bat assembleDebug` and `gradlew.bat testDebugUnitTest` — both must pass
- [ ] Write the README capability table stating exactly which commands are real and which degrade to a Settings panel

---

## Self-Review Notes

- Spec coverage: sections 3-11 of the spec map to tasks 1-8 in order. The spec's offline mode is Task 4's `LocalIntentMatcher`; capability honesty is Task 5's `ToolResult.NotSupported`; the confirmation requirement is Task 5's `ConfirmationGate` surfaced by Task 8's dialog.
- Naming locked here and used unchanged in later tasks: `JarvisStateMachine.state`, `AIProvider.chat`, `ToolResult`, `AgentLoop.run`, `AccessibilityController`, `OrbCanvas`.
- Known gap, accepted: no instrumented tests. Nothing on this workstation can run them, and writing tests that are never executed would be worse than saying so.
