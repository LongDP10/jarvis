package com.jarvis.assistant.ai

import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.ToolCall
import com.jarvis.assistant.utils.TextNormalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The offline fast path.
 *
 * Matches the handful of commands that make up most of what anyone actually
 * says to a phone assistant, and turns them straight into a tool call with no
 * network round trip, no token cost and no latency. This is what makes JARVIS
 * usable on a train with no signal, and it is why "offline mode" in the spec is
 * a real feature rather than an aspiration.
 *
 * Two rules keep it honest:
 *
 *  - It returns null for anything it is not certain about. A wrong fast-path
 *    match is worse than a slow correct one.
 *  - It refuses outright as soon as the utterance looks like more than one
 *    instruction. Half-executing "open YouTube and search for X" -- opening the
 *    app and quietly dropping the search -- is the single worst failure this
 *    layer could produce, so a connector word sends the whole thing to the model.
 *
 * Matching runs on diacritic-stripped text, so Vietnamese works whether or not
 * the recogniser or the user supplied accents.
 */
@Singleton
class LocalIntentMatcher @Inject constructor() {

    fun match(text: String, language: Language): ToolCall? {
        val words = text.trim().split(WHITESPACE).filter { it.isNotBlank() }
        if (words.isEmpty()) return null

        val normalisedWords = words.map { TextNormalizer.normalise(it).trim(*PUNCTUATION) }
        val joined = normalisedWords.joinToString(" ")
        if (joined.isBlank()) return null

        // Anything that joins two instructions goes to the model untouched.
        if (CONNECTORS.any { joined.contains(it) }) return null

        return matchDevice(joined)
            ?: matchMedia(joined)
            ?: matchNavigation(joined)
            ?: matchScroll(joined)
            ?: matchQuery(joined)
            ?: matchOpenApp(words, normalisedWords, joined)
    }

    // ------------------------------------------------------------- device

    private fun matchDevice(joined: String): ToolCall? {
        if (joined.containsAny("screenshot", "chup man hinh", "chup anh man hinh", "chup lai man hinh")) {
            return ToolCall.of("take_screenshot")
        }

        if (joined.containsAny("turn off the flashlight", "turn off flashlight", "turn off the torch", "tat den pin", "tat den flash")) {
            return ToolCall.of("toggle_flashlight", "on" to "false")
        }
        if (joined.containsAny("turn on the flashlight", "turn on flashlight", "turn on the torch", "bat den pin", "bat den flash", "bat flash")) {
            return ToolCall.of("toggle_flashlight", "on" to "true")
        }

        SET_VOLUME.find(joined)?.let { result ->
            val percent = result.groupValues[1].toIntOrNull()
            if (percent != null) return ToolCall.of("set_volume", "percent" to percent.toString())
        }

        // Checked before "mute" so the un- prefix is not swallowed.
        if (joined.containsAny("unmute", "bo tat tieng", "mo tieng")) {
            return ToolCall.of("mute", "muted" to "false")
        }
        if (joined.containsAny("mute", "tat tieng", "im lang")) {
            return ToolCall.of("mute", "muted" to "true")
        }

        if (joined.containsAny("volume up", "increase volume", "louder", "turn it up", "tang am luong", "to hon", "tang tieng")) {
            return ToolCall.of("increase_volume")
        }
        if (joined.containsAny("volume down", "decrease volume", "quieter", "turn it down", "giam am luong", "nho hon", "giam tieng")) {
            return ToolCall.of("decrease_volume")
        }

        return null
    }

    // -------------------------------------------------------------- media

    private fun matchMedia(joined: String): ToolCall? = when {
        joined.containsAny("next track", "next song", "skip track", "bai tiep theo", "bai hat tiep theo", "bai ke tiep") ->
            ToolCall.of("next_track")

        joined.containsAny("previous track", "previous song", "bai truoc", "bai hat truoc") ->
            ToolCall.of("previous_track")

        joined.containsAny("pause", "tam dung", "dung phat", "dung nhac") ->
            ToolCall.of("pause_media")

        joined == "play" || joined.containsAny("play music", "resume playback", "resume music", "phat nhac", "mo nhac", "tiep tuc phat") ->
            ToolCall.of("play_media")

        else -> null
    }

    // --------------------------------------------------------- navigation

    private fun matchNavigation(joined: String): ToolCall? = when {
        joined.containsAny("go home", "home screen", "ve man hinh chinh", "ve trang chu", "ve home") ->
            ToolCall.of("go_home")

        joined == "back" || joined.containsAny("go back", "quay lai", "tro lai", "quay ve truoc") ->
            ToolCall.of("go_back")

        joined.containsAny("recent apps", "recents", "ung dung gan day", "cac ung dung gan day") ->
            ToolCall.of("open_recents")

        joined.containsAny("open notifications", "notification shade", "mo thong bao", "keo thong bao") ->
            ToolCall.of("open_notifications")

        joined.containsAny("quick settings", "cai dat nhanh") ->
            ToolCall.of("open_quick_settings")

        joined.containsAny("open settings", "mo cai dat", "mo cai dat dien thoai") ->
            ToolCall.of("open_settings")

        else -> null
    }

    private fun matchScroll(joined: String): ToolCall? {
        val result = SCROLL.find(joined) ?: return null
        val direction = when (result.groupValues[2]) {
            "up", "len" -> "up"
            "left", "trai", "sang trai" -> "left"
            "right", "phai", "sang phai" -> "right"
            else -> "down"
        }
        val times = REPEAT.find(joined)?.groupValues?.get(1)?.toIntOrNull()
        return if (times != null) {
            ToolCall.of("scroll", "direction" to direction, "times" to times.toString())
        } else {
            ToolCall.of("scroll", "direction" to direction)
        }
    }

    private fun matchQuery(joined: String): ToolCall? = when {
        joined.containsAny("what time is it", "what is the time", "may gio roi", "bay gio la may gio", "mien gio") ->
            ToolCall.of("get_time")

        joined.containsAny("battery level", "battery percentage", "how much battery", "con bao nhieu pin", "pin con bao nhieu", "muc pin") ->
            ToolCall.of("get_battery_level")

        else -> null
    }

    // ----------------------------------------------------------- open app

    private fun matchOpenApp(
        words: List<String>,
        normalisedWords: List<String>,
        joined: String,
    ): ToolCall? {
        // Longest prefixes first, so "khoi dong" is not shadowed by "mo".
        val prefix = OPEN_PREFIXES
            .sortedByDescending { it.size }
            .firstOrNull { prefix ->
                normalisedWords.size > prefix.size &&
                    normalisedWords.take(prefix.size) == prefix
            } ?: return null

        val remaining = words.drop(prefix.size)
        if (remaining.isEmpty()) return null
        // An app name is a name, not a sentence. Anything longer is a request
        // the model should be reading, not a launch command.
        if (remaining.size > MAX_APP_NAME_WORDS) return null
        if (joined.containsAny("search", "tim kiem", "tim ")) return null

        val appName = remaining.joinToString(" ").trim(*PUNCTUATION).trim()
        if (appName.isEmpty()) return null

        return ToolCall.of("open_app", "app" to appName)
    }

    private fun String.containsAny(vararg needles: String): Boolean =
        needles.any { this.contains(it) }

    private companion object {
        const val MAX_APP_NAME_WORDS = 4

        val WHITESPACE = Regex("\\s+")
        val PUNCTUATION = charArrayOf('.', ',', '!', '?', ';', ':', '"', '\'')

        /**
         * Words that mean "and then do something else". Their presence anywhere
         * in the utterance disqualifies the whole fast path.
         */
        val CONNECTORS = listOf(
            " and ", " then ", " after that ", " also ",
            " roi ", " va ", " sau do ", " tiep theo do ", " xong ",
        )

        val OPEN_PREFIXES: List<List<String>> = listOf(
            listOf("open"),
            listOf("launch"),
            listOf("start"),
            listOf("mo"),
            listOf("bat"),
            listOf("vao"),
            listOf("khoi", "dong"),
            listOf("open", "up"),
            listOf("mo", "ung", "dung"),
        )

        val SET_VOLUME = Regex("(?:volume|am luong)\\s*(?:to|len|thanh)?\\s*(\\d{1,3})")
        val SCROLL = Regex("(scroll|swipe|cuon|vuot|keo)\\s+(up|down|left|right|len|xuong|trai|phai)")
        val REPEAT = Regex("(\\d{1,2})\\s*(?:times|lan)")
    }
}
