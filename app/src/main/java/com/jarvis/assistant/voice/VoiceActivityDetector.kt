package com.jarvis.assistant.voice

/**
 * Tracks whether the user is currently speaking, from the amplitude samples the
 * recogniser already produces.
 *
 * Android's recogniser does its own endpointing, so this is not what ends a
 * normal command. It exists for the cases the recogniser does not cover: keeping
 * the wake-word loop from restarting into a room that has been silent for a
 * while, and letting the user talk over JARVIS mid-sentence.
 *
 * Pure and allocation-free so it can run per audio frame and be unit tested.
 */
class VoiceActivityDetector(
    private val speechThreshold: Float = DEFAULT_SPEECH_THRESHOLD,
    private val silenceThreshold: Float = DEFAULT_SILENCE_THRESHOLD,
    private val silenceTimeoutMs: Long = DEFAULT_SILENCE_TIMEOUT_MS,
) {

    var isSpeaking: Boolean = false
        private set

    private var lastSpeechAtMs: Long = 0
    private var hasHeardSpeech: Boolean = false

    /**
     * @return true when this sample flipped the detector into the speaking state.
     */
    fun onAmplitude(amplitude: Float, nowMs: Long = System.currentTimeMillis()): Boolean {
        // Hysteresis: a single loud sample starts speech, but it takes a
        // sustained quiet period to end it. Using one threshold for both makes
        // the state chatter on every pause between words.
        if (amplitude >= speechThreshold) {
            lastSpeechAtMs = nowMs
            hasHeardSpeech = true
            if (!isSpeaking) {
                isSpeaking = true
                return true
            }
            return false
        }

        if (isSpeaking && amplitude <= silenceThreshold &&
            nowMs - lastSpeechAtMs >= silenceTimeoutMs
        ) {
            isSpeaking = false
        }
        return false
    }

    /** True once speech has been heard and the trailing silence has elapsed. */
    fun isUtteranceComplete(nowMs: Long = System.currentTimeMillis()): Boolean =
        hasHeardSpeech && !isSpeaking && nowMs - lastSpeechAtMs >= silenceTimeoutMs

    /** True when nothing has been said at all since [reset]. */
    fun hasHeardNothing(): Boolean = !hasHeardSpeech

    fun reset() {
        isSpeaking = false
        hasHeardSpeech = false
        lastSpeechAtMs = 0
    }

    companion object {
        const val DEFAULT_SPEECH_THRESHOLD = 0.22f
        const val DEFAULT_SILENCE_THRESHOLD = 0.12f
        const val DEFAULT_SILENCE_TIMEOUT_MS = 1200L
    }
}
