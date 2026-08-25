package com.jarvis.assistant.voice

import com.jarvis.assistant.core.Language
import com.jarvis.assistant.utils.TextNormalizer
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Decides which language an utterance was in, so that Auto mode can answer in
 * the language the user actually spoke.
 *
 * Two signals, because either one alone is wrong often enough to matter:
 *
 *  - Vietnamese diacritics are near-proof, but Android's recogniser and users
 *    typing on a plain keyboard both produce unaccented Vietnamese constantly.
 *  - So an unaccented vocabulary check runs as well. Its word list deliberately
 *    excludes Vietnamese syllables that are also English words ("man", "tin",
 *    "bat", "pin", "long", "the", "so", "am", "on"); those are matched only as
 *    part of a two-word phrase, where they are unambiguous.
 *
 * Returns [Language.AUTO] when there is genuinely no evidence. That is not a
 * failure -- it tells the caller to fall back to the configured language rather
 * than coin-flip.
 */
@Singleton
class LanguageDetector @Inject constructor() {

    fun detect(text: String): Language {
        if (text.isBlank()) return Language.AUTO

        val lower = text.lowercase(Locale.ROOT)
        val diacritics = lower.count { it in VIETNAMESE_CHARS }

        val plain = stripDiacritics(lower)
        val words = plain.split(NON_WORD).filter { it.isNotBlank() }
        if (words.isEmpty() && diacritics == 0) return Language.AUTO

        val wordSet = words.toSet()
        val vietnameseWords = wordSet.count { it in VIETNAMESE_WORDS }
        val englishWords = wordSet.count { it in ENGLISH_WORDS }
        val vietnamesePhrases = VIETNAMESE_PHRASES.count { plain.contains(it) }

        val vietnameseScore = diacritics * DIACRITIC_WEIGHT +
            vietnameseWords * WORD_WEIGHT +
            vietnamesePhrases * PHRASE_WEIGHT
        val englishScore = englishWords * WORD_WEIGHT

        return when {
            vietnameseScore > englishScore -> Language.VIETNAMESE
            englishScore > vietnameseScore -> Language.ENGLISH
            else -> Language.AUTO
        }
    }

    /** "Mở ứng dụng" -> "mo ung dung". */
    fun stripDiacritics(text: String): String = TextNormalizer.stripDiacritics(text)

    private companion object {
        const val DIACRITIC_WEIGHT = 2
        const val WORD_WEIGHT = 2
        const val PHRASE_WEIGHT = 3

        val NON_WORD = Regex("[^a-z0-9]+")

        val VIETNAMESE_CHARS: Set<Char> = (
            "àáạảãâầấậẩẫăằắặẳẵ" +
                "èéẹẻẽêềếệểễ" +
                "ìíịỉĩ" +
                "òóọỏõôồốộổỗơờớợởỡ" +
                "ùúụủũưừứựửữ" +
                "ỳýỵỷỹ" +
                "đ"
            ).toSet()

        /**
         * Unaccented Vietnamese command vocabulary. Anything that collides with a
         * common English word is left out on purpose; see the class comment.
         */
        val VIETNAMESE_WORDS: Set<String> = setOf(
            "mo", "dong", "tat", "tang", "giam", "luong", "thoai", "dien",
            "ung", "dung", "hinh", "chup", "anh", "goi", "nhan", "gui",
            "tim", "kiem", "giup", "toi", "minh", "nhac", "phat",
            "quay", "lai", "chinh", "cai", "dat", "den", "ket", "noi",
            "mang", "thoi", "tiet", "hom", "nay", "gio", "bao", "nhieu",
            "xin", "chao", "cam", "vui", "lam", "khong", "duoc", "hay",
            "roi", "nua", "tiep", "tuc", "dau", "tien", "thu", "hai",
            "cuoi", "cung", "trang", "chu", "thong", "nghe",
            "xoa", "luu", "moi", "muon", "cho", "voi", "va", "cua", "ve",
            "sau", "truoc", "ngay", "thang",
        )

        /**
         * Phrases whose individual syllables are ambiguous but whose pairing is
         * not. Matched against the whole unaccented string.
         */
        val VIETNAMESE_PHRASES: List<String> = listOf(
            "ung dung", "dien thoai", "am luong", "man hinh", "nhan tin",
            "cam on", "lam on", "tin nhan", "bat den", "tat den", "may bay",
            "may anh", "the nao", "bao nhieu", "hom nay", "ngay mai",
        )

        val ENGLISH_WORDS: Set<String> = setOf(
            "open", "close", "and", "the", "for", "a", "an", "is", "are",
            "my", "me", "please", "can", "you", "what", "when", "where",
            "how", "who", "why", "search", "play", "pause", "take", "turn",
            "up", "down", "go", "back", "home", "screenshot", "photo",
            "call", "send", "message", "tutorial", "video", "weather",
            "time", "today", "tomorrow", "thanks", "thank", "hello", "hi",
            "show", "tell", "find", "set", "make", "start", "stop", "next",
            "previous", "first", "second", "last", "then", "with", "about",
            "increase", "decrease", "volume", "brightness", "flashlight",
            "screen", "app", "apps", "settings", "scroll", "tap", "swipe",
        )
    }
}
