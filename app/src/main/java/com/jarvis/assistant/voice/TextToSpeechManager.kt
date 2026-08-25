package com.jarvis.assistant.voice

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.data.settings.JarvisSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

sealed interface TtsEvent {
    data object Start : TtsEvent
    /** 0..1 through the utterance, derived from real character ranges. */
    data class Progress(val fraction: Float) : TtsEvent
    data object Done : TtsEvent
    data class Failed(val reason: String) : TtsEvent
}

data class VoiceOption(
    val name: String,
    val label: String,
    val gender: VoiceGender,
    val isNetworkOnly: Boolean,
)

enum class VoiceGender { MALE, FEMALE, UNKNOWN }

/**
 * Speaks, and reports how far through it is so the orb can pulse in time with
 * the audio rather than to an arbitrary animation curve.
 *
 * Uses whatever TTS engine the user has installed. On a Galaxy device that is
 * usually Samsung TTS or Google TTS; both ship Vietnamese and English voices,
 * but neither is guaranteed, so [isLanguageAvailable] is what the settings
 * screen checks before offering a choice.
 */
@Singleton
class TextToSpeechManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val initLock = Mutex()
    private var engine: TextToSpeech? = null

    private suspend fun engine(): TextToSpeech? = initLock.withLock {
        engine?.let { return it }
        val created = suspendCancellableCoroutine { continuation ->
            var tts: TextToSpeech? = null
            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    continuation.resume(tts)
                } else {
                    runCatching { tts?.shutdown() }
                    continuation.resume(null)
                }
            }
        }
        engine = created
        created
    }

    /** Warm the engine up so the first spoken reply is not delayed by init. */
    suspend fun preload() {
        engine()
    }

    suspend fun isLanguageAvailable(language: Language): Boolean {
        val tts = engine() ?: return false
        val result = tts.isLanguageAvailable(language.locale)
        return result == TextToSpeech.LANG_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_AVAILABLE ||
            result == TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE
    }

    suspend fun availableVoices(language: Language): List<VoiceOption> {
        val tts = engine() ?: return emptyList()
        val target = language.locale.language
        return runCatching {
            // Network-only voices are kept in the list rather than hidden: they
            // work perfectly well online, and silently dropping them would make
            // a device with only network voices look like it has none at all.
            tts.voices.orEmpty()
                .filter { it.locale.language == target }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
                .map { it.toOption() }
        }.getOrDefault(emptyList())
    }

    /**
     * Speaks [text] and emits progress. The flow completes when the utterance
     * finishes; cancelling it stops playback, which is what makes "stop talking"
     * work as a barge-in.
     */
    fun speak(
        text: String,
        language: Language,
        settings: JarvisSettings,
    ): Flow<TtsEvent> = callbackFlow {
        val tts = engine()
        if (tts == null) {
            trySend(TtsEvent.Failed("Text-to-speech engine unavailable"))
            close()
            return@callbackFlow
        }
        if (text.isBlank()) {
            trySend(TtsEvent.Done)
            close()
            return@callbackFlow
        }

        val utteranceId = UUID.randomUUID().toString()
        val length = text.length.toFloat()

        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String?) {
                if (id == utteranceId) trySend(TtsEvent.Start)
            }

            override fun onRangeStart(id: String?, start: Int, end: Int, frame: Int) {
                if (id == utteranceId) {
                    trySend(TtsEvent.Progress((end / length).coerceIn(0f, 1f)))
                }
            }

            override fun onDone(id: String?) {
                if (id == utteranceId) {
                    trySend(TtsEvent.Progress(1f))
                    trySend(TtsEvent.Done)
                    close()
                }
            }

            @Deprecated("Superseded by onError(String, Int)", ReplaceWith(""))
            override fun onError(id: String?) {
                if (id == utteranceId) {
                    trySend(TtsEvent.Failed("Playback failed"))
                    close()
                }
            }

            override fun onError(id: String?, errorCode: Int) {
                if (id == utteranceId) {
                    trySend(TtsEvent.Failed("Playback failed (code $errorCode)"))
                    close()
                }
            }
        })

        applyVoiceSettings(tts, language, settings)

        val result = tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) {
            trySend(TtsEvent.Failed("Text-to-speech rejected the request"))
            close()
        }

        awaitClose {
            runCatching {
                tts.stop()
                tts.setOnUtteranceProgressListener(null)
            }
        }
    }

    suspend fun stop() {
        runCatching { engine()?.stop() }
    }

    fun shutdown() {
        runCatching { engine?.shutdown() }
        engine = null
    }

    private fun applyVoiceSettings(
        tts: TextToSpeech,
        language: Language,
        settings: JarvisSettings,
    ) {
        tts.setSpeechRate(settings.speechRate)
        tts.setPitch(settings.pitch)
        tts.language = language.locale

        val preferred = when (language) {
            Language.VIETNAMESE -> settings.voiceNameVi
            else -> settings.voiceNameEn
        }
        if (preferred != null) {
            val match = runCatching { tts.voices.orEmpty().firstOrNull { it.name == preferred } }
                .getOrNull()
            // A voice can vanish when the engine is updated or a pack is removed,
            // in which case the engine default is the right answer, not an error.
            if (match != null) tts.voice = match
        }
    }

    private fun Voice.toOption(): VoiceOption {
        val lowered = name.lowercase(Locale.ROOT)
        val gender = when {
            lowered.contains("female") || lowered.contains("#f") -> VoiceGender.FEMALE
            lowered.contains("male") || lowered.contains("#m") -> VoiceGender.MALE
            else -> VoiceGender.UNKNOWN
        }
        return VoiceOption(
            name = name,
            label = buildLabel(gender),
            gender = gender,
            isNetworkOnly = isNetworkConnectionRequired,
        )
    }

    private fun Voice.buildLabel(gender: VoiceGender): String {
        val genderPart = when (gender) {
            VoiceGender.MALE -> "Male"
            VoiceGender.FEMALE -> "Female"
            VoiceGender.UNKNOWN -> "Voice"
        }
        val qualityPart = when {
            quality >= Voice.QUALITY_VERY_HIGH -> "very high"
            quality >= Voice.QUALITY_HIGH -> "high"
            quality >= Voice.QUALITY_NORMAL -> "normal"
            else -> "low"
        }
        val suffix = name.substringAfterLast('-', "").take(12)
        return "$genderPart ($qualityPart)${if (suffix.isBlank()) "" else " · $suffix"}"
    }
}
