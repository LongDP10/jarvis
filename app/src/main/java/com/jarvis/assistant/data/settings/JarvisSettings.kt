package com.jarvis.assistant.data.settings

import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.ProviderId

enum class VoiceProcessing {
    /** Always use the network recogniser. Best accuracy, needs a connection. */
    CLOUD,

    /** Force EXTRA_PREFER_OFFLINE. Only works if a language pack is installed. */
    LOCAL,

    /** Offline first, network when offline recognition returns nothing usable. */
    HYBRID,
    ;

    companion object {
        fun fromName(name: String?): VoiceProcessing =
            entries.firstOrNull { it.name == name } ?: HYBRID
    }
}

enum class OrbCorner {
    TOP_LEFT,
    TOP_RIGHT,
    BOTTOM_LEFT,
    BOTTOM_RIGHT,
    ;

    companion object {
        fun fromName(name: String?): OrbCorner =
            entries.firstOrNull { it.name == name } ?: BOTTOM_RIGHT
    }
}

/**
 * Everything the user can change, in one immutable snapshot. Reading a single
 * object rather than a dozen flows means a consumer can never act on a half
 * updated configuration.
 *
 * API keys are deliberately absent: they live in
 * [com.jarvis.assistant.data.secure.SecureKeyStore] and are never held in a
 * value class that gets logged, copied into state, or handed to the UI.
 */
data class JarvisSettings(
    val language: Language = Language.AUTO,
    val voiceProcessing: VoiceProcessing = VoiceProcessing.HYBRID,
    val voiceNameVi: String? = null,
    val voiceNameEn: String? = null,
    val speechRate: Float = 1.0f,
    val pitch: Float = 1.0f,
    val provider: ProviderId = ProviderId.DEFAULT,
    val geminiModel: String = DEFAULT_GEMINI_MODEL,
    val openAiModel: String = DEFAULT_OPENAI_MODEL,
    val anthropicModel: String = DEFAULT_ANTHROPIC_MODEL,
    val ollamaModel: String = DEFAULT_OLLAMA_MODEL,
    val ollamaBaseUrl: String = DEFAULT_OLLAMA_URL,
    val wakeWordEnabled: Boolean = false,
    val overlayEnabled: Boolean = false,
    val alwaysListening: Boolean = false,
    val orbScale: Float = 1.0f,
    val orbCorner: OrbCorner = OrbCorner.BOTTOM_RIGHT,
    val debugLogEnabled: Boolean = false,
    val onboardingComplete: Boolean = false,
) {
    val modelForCurrentProvider: String
        get() = when (provider) {
            ProviderId.GEMINI -> geminiModel
            ProviderId.OPENAI -> openAiModel
            ProviderId.ANTHROPIC -> anthropicModel
            ProviderId.OLLAMA -> ollamaModel
        }

    companion object {
        const val DEFAULT_GEMINI_MODEL = "gemini-2.0-flash"
        const val DEFAULT_OPENAI_MODEL = "gpt-4o-mini"
        const val DEFAULT_ANTHROPIC_MODEL = "claude-opus-5"
        const val DEFAULT_OLLAMA_MODEL = "qwen2.5"
        const val DEFAULT_OLLAMA_URL = "http://192.168.1.10:11434"

        val SPEECH_RATE_RANGE = 0.5f..2.0f
        val PITCH_RANGE = 0.5f..2.0f
        val ORB_SCALE_RANGE = 0.7f..1.6f
    }
}
