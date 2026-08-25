package com.jarvis.assistant.session

import android.util.Log
import com.jarvis.assistant.R
import com.jarvis.assistant.ai.AgentLoop
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.JarvisStateMachine
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.data.repo.CommandLogRepository
import com.jarvis.assistant.data.repo.LogStage
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.di.ApplicationScope
import com.jarvis.assistant.utils.LocalizedStrings
import com.jarvis.assistant.voice.LanguageDetector
import com.jarvis.assistant.voice.SpeechRecognitionManager
import com.jarvis.assistant.voice.SttError
import com.jarvis.assistant.voice.SttEvent
import com.jarvis.assistant.voice.TextToSpeechManager
import com.jarvis.assistant.voice.TtsEvent
import com.jarvis.assistant.voice.WakeWordEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The turn loop: listen, understand, act, speak, go quiet.
 *
 * One instance for the whole process, so the floating orb and the app screen are
 * driving the same session rather than two competing ones. Only one turn runs at
 * a time; starting another cancels the first, which is what makes tapping the
 * orb mid-answer behave like an interruption instead of producing two voices.
 *
 * Runs on the application scope on purpose. A command that outlives the screen
 * that started it -- the user says "open YouTube" and the activity goes away as
 * YouTube launches -- must still finish and still speak its reply.
 */
@Singleton
class JarvisController @Inject constructor(
    private val recognition: SpeechRecognitionManager,
    private val tts: TextToSpeechManager,
    private val agentLoop: AgentLoop,
    private val stateMachine: JarvisStateMachine,
    private val settings: SettingsRepository,
    private val languageDetector: LanguageDetector,
    private val wakeWord: WakeWordEngine,
    private val commandLog: CommandLogRepository,
    private val strings: LocalizedStrings,
    @ApplicationScope private val scope: CoroutineScope,
) {

    private var turnJob: Job? = null
    private var wakeWordJob: Job? = null

    val state = stateMachine.state

    /** Tap the orb, or the wake word fired. */
    fun startVoiceCommand() {
        turnJob?.cancel()
        turnJob = scope.launch { voiceTurn() }
    }

    /** Typed into the chat screen. Same pipeline, different input. */
    fun submitText(text: String) {
        if (text.isBlank()) return
        turnJob?.cancel()
        turnJob = scope.launch { textTurn(text.trim()) }
    }

    fun cancel() {
        turnJob?.cancel()
        turnJob = null
        scope.launch { tts.stop() }
        stateMachine.transition(JarvisState.Cancelled)
        scope.launch {
            delay(CANCEL_LINGER_MS)
            if (stateMachine.state.value is JarvisState.Cancelled) stateMachine.reset()
        }
        resumeWakeWord()
    }

    // -------------------------------------------------------------- turns

    private suspend fun voiceTurn() {
        val configured = settings.current()
        val language = configured.language.resolve()

        pauseWakeWord()
        // Speaking and listening at the same time means JARVIS hears itself.
        tts.stop()

        stateMachine.transition(JarvisState.Listening())

        var finalText: String? = null
        var failure: SttError? = null

        try {
            recognition.listen(language, configured.voiceProcessing).collect { event ->
                when (event) {
                    is SttEvent.Rms -> stateMachine.updateListening(
                        partial = (stateMachine.state.value as? JarvisState.Listening)?.partial.orEmpty(),
                        amplitude = event.amplitude,
                    )

                    is SttEvent.Partial -> stateMachine.updateListening(
                        partial = event.text,
                        amplitude = (stateMachine.state.value as? JarvisState.Listening)?.amplitude ?: 0f,
                    )

                    is SttEvent.Final -> finalText = event.text
                    is SttEvent.Failed -> failure = event.error
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Recognition failed", e)
            failure = SttError.OTHER
        }

        val heard = finalText
        if (heard.isNullOrBlank()) {
            val message = messageFor(failure, language)
            speakAndSettle(message, language)
            return
        }

        handle(heard, language)
    }

    private suspend fun textTurn(text: String) {
        pauseWakeWord()
        val language = settings.current().language.resolve(languageDetector.detect(text))
        handle(text, language)
    }

    private suspend fun handle(userText: String, fallbackLanguage: Language) {
        stateMachine.transition(
            JarvisState.Processing(strings.get(fallbackLanguage, R.string.state_thinking)),
        )
        val outcome = runCatching { agentLoop.run(userText) }.getOrElse { error ->
            Log.e(TAG, "Agent loop failed", error)
            stateMachine.fail(error.message ?: "")
            speakAndSettle(
                strings.get(fallbackLanguage, R.string.error_not_understood),
                fallbackLanguage,
            )
            return
        }
        speakAndSettle(outcome.reply, outcome.language)
    }

    // ------------------------------------------------------------- speech

    private suspend fun speakAndSettle(text: String, language: Language) {
        speak(text, language)
        stateMachine.reset()
        resumeWakeWord()
    }

    private suspend fun speak(text: String, language: Language) {
        if (text.isBlank()) return
        val configured = settings.current()
        stateMachine.transition(JarvisState.Speaking(text))
        commandLog.log(commandLog.newTurnId(), LogStage.TTS, "speak", text)

        try {
            tts.speak(text, language, configured).collect { event ->
                when (event) {
                    is TtsEvent.Progress -> stateMachine.updateSpeaking(event.fraction)
                    is TtsEvent.Failed -> Log.w(TAG, "TTS failed: ${event.reason}")
                    else -> Unit
                }
            }
        } catch (e: Exception) {
            // A missing or broken TTS engine must not swallow the answer; the
            // text is already on screen either way.
            Log.w(TAG, "Speech playback failed", e)
        }
    }

    private fun messageFor(error: SttError?, language: Language): String = when (error) {
        SttError.PERMISSION_DENIED -> strings.get(
            language,
            R.string.error_needs_permission,
            strings.get(language, R.string.permission_microphone),
        )
        SttError.NETWORK -> strings.get(language, R.string.error_no_network)
        SttError.UNAVAILABLE -> strings.get(language, R.string.error_no_speech)
        else -> strings.get(language, R.string.error_no_speech)
    }

    // ---------------------------------------------------------- wake word

    fun startWakeWord() {
        if (wakeWordJob != null) return
        wakeWordJob = scope.launch {
            wakeWord.start(scope) { startVoiceCommand() }
        }
    }

    fun stopWakeWord() {
        wakeWord.stop()
        wakeWordJob?.cancel()
        wakeWordJob = null
    }

    private suspend fun pauseWakeWord() {
        wakeWord.pause()
        // The recogniser needs a beat to release the microphone before another
        // session can claim it, or the new one fails with RECOGNIZER_BUSY.
        delay(MIC_HANDOVER_MS)
    }

    private fun resumeWakeWord() {
        scope.launch {
            delay(MIC_HANDOVER_MS)
            if (settings.current().wakeWordEnabled) wakeWord.resume()
        }
    }

    private companion object {
        const val TAG = "JarvisController"
        const val MIC_HANDOVER_MS = 300L
        const val CANCEL_LINGER_MS = 1200L
    }
}
