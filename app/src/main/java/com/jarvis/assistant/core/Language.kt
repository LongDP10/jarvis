package com.jarvis.assistant.core

import java.util.Locale

/**
 * The user's language preference. [AUTO] is not a language JARVIS can speak, it
 * is an instruction to work out which of the other two to use from what the user
 * actually said, so anything that needs a concrete locale must call [resolve].
 */
enum class Language(val tag: String) {
    VIETNAMESE("vi-VN"),
    ENGLISH("en-US"),
    AUTO("auto"),
    ;

    val locale: Locale
        get() = when (this) {
            VIETNAMESE -> Locale.forLanguageTag("vi-VN")
            ENGLISH -> Locale.forLanguageTag("en-US")
            AUTO -> Locale.getDefault()
        }

    /**
     * Collapses [AUTO] into a real language. [detected] is what the language
     * detector concluded from the user's utterance; when there is nothing to go
     * on the device locale decides, which means a Vietnamese phone answers in
     * Vietnamese on the very first command.
     */
    fun resolve(detected: Language? = null): Language = when {
        this != AUTO -> this
        detected != null && detected != AUTO -> detected
        Locale.getDefault().language == "vi" -> VIETNAMESE
        else -> ENGLISH
    }

    companion object {
        fun fromTag(tag: String?): Language =
            entries.firstOrNull { it.tag == tag } ?: AUTO
    }
}
