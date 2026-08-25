package com.jarvis.assistant.core

/**
 * Lives in core rather than in the ai package because settings and the secure
 * key store both key off it, and neither of those has any business depending on
 * the AI layer.
 */
enum class ProviderId(val storageKey: String, val displayName: String) {
    GEMINI("gemini", "Google Gemini"),
    OPENAI("openai", "OpenAI"),
    ANTHROPIC("anthropic", "Anthropic Claude"),
    OLLAMA("ollama", "Ollama (local)"),
    ;

    /** Ollama runs on the user's own machine, so it needs a URL, not a key. */
    val requiresApiKey: Boolean get() = this != OLLAMA

    companion object {
        val DEFAULT = OLLAMA

        fun fromKey(key: String?): ProviderId =
            entries.firstOrNull { it.storageKey == key } ?: DEFAULT
    }
}
