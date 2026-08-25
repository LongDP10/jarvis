package com.jarvis.assistant.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.data.settings.VoiceProcessing
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject
import javax.inject.Singleton

sealed interface SttEvent {
    data object ReadyForSpeech : SttEvent
    data class Rms(val amplitude: Float) : SttEvent
    data class Partial(val text: String) : SttEvent
    data object EndOfSpeech : SttEvent
    data class Final(val text: String) : SttEvent
    data class Failed(val error: SttError) : SttEvent
}

enum class SttError {
    NO_MATCH,
    SPEECH_TIMEOUT,
    PERMISSION_DENIED,
    NETWORK,
    BUSY,
    UNAVAILABLE,
    OTHER,
}

/**
 * Wraps Android's [SpeechRecognizer] as a cold flow.
 *
 * The recogniser is not thread-safe and must be created and driven on the main
 * looper, which is why the whole producer runs on [Dispatchers.Main]. A fresh
 * instance is created per call and destroyed in awaitClose: reusing one across
 * sessions is the usual cause of ERROR_RECOGNIZER_BUSY loops.
 */
@Singleton
class SpeechRecognitionManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    /**
     * @param language must already be resolved; passing [Language.AUTO] would
     *   leave the recogniser without a locale, so it falls back to the device
     *   default rather than failing.
     */
    fun listen(
        language: Language,
        processing: VoiceProcessing = VoiceProcessing.HYBRID,
        partialResults: Boolean = true,
    ): Flow<SttEvent> = callbackFlow {
        if (!hasMicrophonePermission()) {
            trySend(SttEvent.Failed(SttError.PERMISSION_DENIED))
            close()
            return@callbackFlow
        }
        if (!isAvailable()) {
            trySend(SttEvent.Failed(SttError.UNAVAILABLE))
            close()
            return@callbackFlow
        }

        val recognizer = SpeechRecognizer.createSpeechRecognizer(context)
        var finished = false

        val listener = object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                trySend(SttEvent.ReadyForSpeech)
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) {
                trySend(SttEvent.Rms(normaliseRms(rmsdB)))
            }

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                trySend(SttEvent.EndOfSpeech)
            }

            override fun onError(error: Int) {
                if (finished) return
                finished = true
                trySend(SttEvent.Failed(error.toSttError()))
                close()
            }

            override fun onResults(results: Bundle?) {
                if (finished) return
                finished = true
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isEmpty()) {
                    trySend(SttEvent.Failed(SttError.NO_MATCH))
                } else {
                    trySend(SttEvent.Final(text))
                }
                close()
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    ?.trim()
                    .orEmpty()
                if (text.isNotEmpty()) trySend(SttEvent.Partial(text))
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }

        recognizer.setRecognitionListener(listener)
        recognizer.startListening(buildIntent(language, processing, partialResults))

        awaitClose {
            runCatching {
                recognizer.stopListening()
                recognizer.cancel()
                recognizer.destroy()
            }
        }
    }.flowOn(Dispatchers.Main)

    private fun buildIntent(
        language: Language,
        processing: VoiceProcessing,
        partialResults: Boolean,
    ): Intent {
        val tag = if (language == Language.AUTO) {
            java.util.Locale.getDefault().toLanguageTag()
        } else {
            language.tag
        }
        return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, tag)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, tag)
            putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, false)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            // Only honoured when the user has downloaded an offline pack; the
            // recogniser silently ignores it otherwise, which is why HYBRID can
            // set it optimistically and just fall through on NO_MATCH.
            putExtra(
                RecognizerIntent.EXTRA_PREFER_OFFLINE,
                processing != VoiceProcessing.CLOUD,
            )
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_MS)
            putExtra(
                RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS,
                SILENCE_MS,
            )
        }
    }

    private fun Int.toSttError(): SttError = when (this) {
        SpeechRecognizer.ERROR_NO_MATCH -> SttError.NO_MATCH
        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SttError.SPEECH_TIMEOUT
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SttError.PERMISSION_DENIED
        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> SttError.NETWORK
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> SttError.BUSY
        SpeechRecognizer.ERROR_CLIENT, SpeechRecognizer.ERROR_SERVER -> SttError.OTHER
        else -> SttError.OTHER
    }

    private companion object {
        const val SILENCE_MS = 1200L

        /**
         * onRmsChanged reports roughly -2 dB (silence) to 10 dB (loud speech).
         * Mapped to 0..1 so the orb can drive its animation from it directly.
         */
        fun normaliseRms(rmsdB: Float): Float = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
    }
}
