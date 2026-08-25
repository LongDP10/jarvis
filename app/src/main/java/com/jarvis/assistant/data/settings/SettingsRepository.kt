package com.jarvis.assistant.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.ProviderId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {

    val settings: Flow<JarvisSettings> = dataStore.data.map { prefs ->
        JarvisSettings(
            language = Language.fromTag(prefs[Keys.LANGUAGE]),
            voiceProcessing = VoiceProcessing.fromName(prefs[Keys.VOICE_PROCESSING]),
            voiceNameVi = prefs[Keys.VOICE_VI],
            voiceNameEn = prefs[Keys.VOICE_EN],
            speechRate = prefs[Keys.SPEECH_RATE] ?: 1.0f,
            pitch = prefs[Keys.PITCH] ?: 1.0f,
            provider = ProviderId.fromKey(prefs[Keys.PROVIDER]),
            geminiModel = prefs[Keys.GEMINI_MODEL] ?: JarvisSettings.DEFAULT_GEMINI_MODEL,
            openAiModel = prefs[Keys.OPENAI_MODEL] ?: JarvisSettings.DEFAULT_OPENAI_MODEL,
            anthropicModel = prefs[Keys.ANTHROPIC_MODEL] ?: JarvisSettings.DEFAULT_ANTHROPIC_MODEL,
            ollamaModel = prefs[Keys.OLLAMA_MODEL] ?: JarvisSettings.DEFAULT_OLLAMA_MODEL,
            ollamaBaseUrl = prefs[Keys.OLLAMA_URL] ?: JarvisSettings.DEFAULT_OLLAMA_URL,
            wakeWordEnabled = prefs[Keys.WAKE_WORD] ?: false,
            overlayEnabled = prefs[Keys.OVERLAY] ?: false,
            alwaysListening = prefs[Keys.ALWAYS_LISTENING] ?: false,
            orbScale = prefs[Keys.ORB_SCALE] ?: 1.0f,
            orbCorner = OrbCorner.fromName(prefs[Keys.ORB_CORNER]),
            debugLogEnabled = prefs[Keys.DEBUG_LOG] ?: false,
            onboardingComplete = prefs[Keys.ONBOARDING] ?: false,
        )
    }

    /**
     * For the many call sites -- tools, services, the agent loop -- that need the
     * current value once and have no business collecting a flow for it.
     */
    suspend fun current(): JarvisSettings = settings.first()

    suspend fun setLanguage(language: Language) = put(Keys.LANGUAGE, language.tag)

    suspend fun setVoiceProcessing(mode: VoiceProcessing) = put(Keys.VOICE_PROCESSING, mode.name)

    suspend fun setVoice(language: Language, voiceName: String?) {
        val key = if (language == Language.VIETNAMESE) Keys.VOICE_VI else Keys.VOICE_EN
        dataStore.edit { prefs ->
            if (voiceName == null) prefs.remove(key) else prefs[key] = voiceName
        }
    }

    suspend fun setSpeechRate(rate: Float) =
        put(Keys.SPEECH_RATE, rate.coerceIn(JarvisSettings.SPEECH_RATE_RANGE))

    suspend fun setPitch(pitch: Float) =
        put(Keys.PITCH, pitch.coerceIn(JarvisSettings.PITCH_RANGE))

    suspend fun setProvider(provider: ProviderId) = put(Keys.PROVIDER, provider.storageKey)

    suspend fun setModel(provider: ProviderId, model: String) {
        val key = when (provider) {
            ProviderId.GEMINI -> Keys.GEMINI_MODEL
            ProviderId.OPENAI -> Keys.OPENAI_MODEL
            ProviderId.ANTHROPIC -> Keys.ANTHROPIC_MODEL
            ProviderId.OLLAMA -> Keys.OLLAMA_MODEL
        }
        put(key, model.trim())
    }

    suspend fun setOllamaBaseUrl(url: String) = put(Keys.OLLAMA_URL, url.trim().trimEnd('/'))

    suspend fun setWakeWordEnabled(enabled: Boolean) = put(Keys.WAKE_WORD, enabled)

    suspend fun setOverlayEnabled(enabled: Boolean) = put(Keys.OVERLAY, enabled)

    suspend fun setAlwaysListening(enabled: Boolean) = put(Keys.ALWAYS_LISTENING, enabled)

    suspend fun setOrbScale(scale: Float) =
        put(Keys.ORB_SCALE, scale.coerceIn(JarvisSettings.ORB_SCALE_RANGE))

    suspend fun setOrbCorner(corner: OrbCorner) = put(Keys.ORB_CORNER, corner.name)

    suspend fun setDebugLogEnabled(enabled: Boolean) = put(Keys.DEBUG_LOG, enabled)

    suspend fun setOnboardingComplete(complete: Boolean) = put(Keys.ONBOARDING, complete)

    private suspend fun <T> put(key: Preferences.Key<T>, value: T) {
        dataStore.edit { prefs -> prefs[key] = value }
    }

    private object Keys {
        val LANGUAGE = stringPreferencesKey("language")
        val VOICE_PROCESSING = stringPreferencesKey("voice_processing")
        val VOICE_VI = stringPreferencesKey("voice_vi")
        val VOICE_EN = stringPreferencesKey("voice_en")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val PITCH = floatPreferencesKey("pitch")
        val PROVIDER = stringPreferencesKey("provider")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
        val OPENAI_MODEL = stringPreferencesKey("openai_model")
        val ANTHROPIC_MODEL = stringPreferencesKey("anthropic_model")
        val OLLAMA_MODEL = stringPreferencesKey("ollama_model")
        val OLLAMA_URL = stringPreferencesKey("ollama_url")
        val WAKE_WORD = booleanPreferencesKey("wake_word")
        val OVERLAY = booleanPreferencesKey("overlay")
        val ALWAYS_LISTENING = booleanPreferencesKey("always_listening")
        val ORB_SCALE = floatPreferencesKey("orb_scale")
        val ORB_CORNER = stringPreferencesKey("orb_corner")
        val DEBUG_LOG = booleanPreferencesKey("debug_log")
        val ONBOARDING = booleanPreferencesKey("onboarding_complete")
    }
}
