package com.jarvis.assistant.voice

import android.util.Log
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.data.settings.SettingsRepository
import com.jarvis.assistant.data.settings.VoiceProcessing
import com.jarvis.assistant.utils.TextNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Wake-word detection built on the platform recogniser.
 *
 * Runs short recognition sessions back to back and matches the transcript
 * against a set of spellings the recogniser actually produces for "Jarvis",
 * including the ones a Vietnamese speaker's pronunciation tends to come out as.
 * Offline is preferred so that, with a language pack installed, nothing is sent
 * anywhere while it idles.
 *
 * Battery cost is real and is why this is off by default and carries a warning
 * in settings.
 */
@Singleton
class SpeechWakeWordEngine @Inject constructor(
    private val recognition: SpeechRecognitionManager,
    private val settings: SettingsRepository,
) : WakeWordEngine {

    private val _isRunning = MutableStateFlow(false)
    override val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private var job: Job? = null
    private var paused = false
    private var lastDetectionAtMs = 0L

    override fun start(scope: CoroutineScope, onDetected: () -> Unit) {
        if (job?.isActive == true) return
        paused = false
        _isRunning.value = true
        job = scope.launch {
            var consecutiveFailures = 0
            while (isActive) {
                if (paused) {
                    delay(PAUSE_POLL_MS)
                    continue
                }

                val heard = listenOnce()
                if (heard == null) {
                    consecutiveFailures++
                    // Back off on repeated failures rather than spinning the
                    // recogniser: a denied permission or a missing engine would
                    // otherwise burn the battery faster than listening does.
                    delay(backoffFor(consecutiveFailures))
                    if (consecutiveFailures >= MAX_FAILURES) {
                        Log.w(TAG, "Wake word listener giving up after $consecutiveFailures failures")
                        break
                    }
                    continue
                }

                consecutiveFailures = 0
                if (matchesWakeWord(heard)) {
                    val now = System.currentTimeMillis()
                    // One spoken phrase can arrive as several partial results;
                    // without this the command handler would be started twice.
                    if (now - lastDetectionAtMs > DEBOUNCE_MS) {
                        lastDetectionAtMs = now
                        onDetected()
                    }
                }
                delay(GAP_MS)
            }
            _isRunning.value = false
        }
    }

    override fun pause() {
        paused = true
    }

    override fun resume() {
        paused = false
    }

    override fun stop() {
        job?.cancel()
        job = null
        paused = false
        _isRunning.value = false
    }

    /**
     * Runs one recognition session and returns whatever was heard, partial
     * results included -- the wake phrase is usually recognised long before the
     * session ends, and waiting for the final result adds a second of latency
     * to every wake.
     */
    private suspend fun listenOnce(): String? {
        val current = settings.current()
        val language = current.language.resolve()
        val processing = when (current.voiceProcessing) {
            VoiceProcessing.CLOUD -> VoiceProcessing.CLOUD
            else -> VoiceProcessing.LOCAL
        }

        var best: String? = null
        try {
            recognition.listen(language, processing, partialResults = true).collect { event ->
                when (event) {
                    is SttEvent.Partial -> {
                        best = event.text
                        if (matchesWakeWord(event.text)) throw WakeDetected(event.text)
                    }
                    is SttEvent.Final -> best = event.text
                    // A failure mid-session is normal here: NO_MATCH just means
                    // the room was quiet. Keep whatever partial we already had.
                    is SttEvent.Failed -> Unit
                    else -> Unit
                }
            }
        } catch (detected: WakeDetected) {
            return detected.text
        }
        return best
    }

    private fun matchesWakeWord(text: String): Boolean {
        val normalised = TextNormalizer.normalise(text)
            .replace(NON_LETTERS, " ")
            .trim()
        if (normalised.isEmpty()) return false
        return TRIGGERS.any { normalised.contains(it) }
    }

    private fun backoffFor(failures: Int): Long =
        (BASE_BACKOFF_MS * failures).coerceAtMost(MAX_BACKOFF_MS)

    private class WakeDetected(val text: String) : Exception(null, null, false, false)

    private companion object {
        const val TAG = "WakeWord"
        const val DEBOUNCE_MS = 2500L
        const val GAP_MS = 250L
        const val PAUSE_POLL_MS = 400L
        const val BASE_BACKOFF_MS = 1500L
        const val MAX_BACKOFF_MS = 15_000L
        const val MAX_FAILURES = 12

        val NON_LETTERS = Regex("[^a-z0-9]+")

        /**
         * What the recogniser actually returns for "Jarvis" in practice. The
         * Vietnamese spellings matter: "Jarvis" spoken by a Vietnamese speaker is
         * routinely transcribed as "gia vit" or "da vit" by the vi-VN model.
         */
        val TRIGGERS = listOf(
            "hey jarvis", "hey jarvi", "hey javis", "hey java",
            "jarvis", "jarvi", "javis", "jervis", "jarvish",
            "gia vit", "gia vis", "da vit", "za vit", "ja vit",
            "hay jarvis", "hay javis", "he jarvis",
        )
    }
}
