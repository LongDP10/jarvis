package com.jarvis.assistant.utils

import java.text.Normalizer
import java.util.Locale

/**
 * Vietnamese text arrives from three places that all spell it differently: the
 * speech recogniser, the user's keyboard, and app labels. Every comparison in
 * the app -- language detection, app-name matching, finding a button by its
 * label -- has to fold those together, so the folding lives in exactly one
 * place.
 */
object TextNormalizer {

    private val COMBINING_MARKS = Regex("\\p{InCombiningDiacriticalMarks}+")
    private val NON_WORD = Regex("[^a-z0-9]+")

    /** "Tìm kiếm" -> "tim kiem". Lowercases and drops accents, keeps spacing. */
    fun normalise(text: String): String =
        stripDiacritics(text.lowercase(Locale.ROOT)).trim()

    fun stripDiacritics(text: String): String {
        // đ/Đ decompose to nothing useful under NFD, so they are handled first.
        val replaced = text.replace('đ', 'd').replace('Đ', 'D')
        return Normalizer.normalize(replaced, Normalizer.Form.NFD)
            .replace(COMBINING_MARKS, "")
    }

    /** Normalised word tokens, empty entries removed. */
    fun tokens(text: String): List<String> =
        normalise(text).split(NON_WORD).filter { it.isNotBlank() }

    /** Collapses punctuation to single spaces: "Zalo - Chat" -> "zalo chat". */
    fun normaliseWords(text: String): String = tokens(text).joinToString(" ")
}
