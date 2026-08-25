package com.jarvis.assistant.ai

import com.jarvis.assistant.R
import com.jarvis.assistant.commands.ToolResult
import com.jarvis.assistant.core.Language
import com.jarvis.assistant.core.ToolCall
import com.jarvis.assistant.utils.LocalizedStrings
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Turns an offline tool result into something worth saying out loud.
 *
 * Only the fast path needs this. When a model is involved it writes its own
 * reply in the right language; when it is not, the tool's own summary is written
 * in English for the model's benefit and would be wrong to read to a Vietnamese
 * speaker. So the offline path gets short, properly localised confirmations
 * keyed off the tool that ran.
 *
 * Failures keep the tool's detail, because a vague "that didn't work" is worse
 * than an awkwardly bilingual explanation of why.
 */
@Singleton
class SpokenResponses @Inject constructor(
    private val strings: LocalizedStrings,
) {

    fun forResult(call: ToolCall, result: ToolResult, language: Language): String =
        when (result) {
            is ToolResult.Success -> success(call, language, result)
            is ToolResult.Cancelled -> strings.get(language, R.string.error_cancelled)
            is ToolResult.RequiresPermission -> result.rationale
            is ToolResult.NotSupported -> strings.get(
                language,
                R.string.error_not_supported,
                result.summary,
            )
            is ToolResult.Failure -> strings.get(language, R.string.error_generic, result.summary)
        }

    private fun success(call: ToolCall, language: Language, result: ToolResult.Success): String =
        when (call.name) {
            "open_app" -> strings.get(
                language,
                R.string.done_opened_app,
                call.string("app").orEmpty(),
            )

            "increase_volume" -> strings.get(language, R.string.done_volume_up)
            "decrease_volume" -> strings.get(language, R.string.done_volume_down)
            "set_volume" -> strings.get(
                language,
                R.string.done_volume_set,
                call.int("percent") ?: 0,
            )

            "mute" -> if (call.boolean("muted") != false) {
                strings.get(language, R.string.done_muted)
            } else {
                strings.get(language, R.string.done_unmuted)
            }

            "toggle_flashlight" -> if (call.boolean("on") != false) {
                strings.get(language, R.string.done_flashlight_on)
            } else {
                strings.get(language, R.string.done_flashlight_off)
            }

            "take_screenshot" -> strings.get(language, R.string.done_screenshot)
            "go_home" -> strings.get(language, R.string.done_home)
            "go_back" -> strings.get(language, R.string.done_back)
            "open_recents" -> strings.get(language, R.string.done_recents)
            "open_notifications" -> strings.get(language, R.string.done_notifications)
            "open_settings", "open_quick_settings" -> strings.get(language, R.string.done_settings)
            "scroll" -> strings.get(language, R.string.done_scroll)
            "play_media" -> strings.get(language, R.string.done_play)
            "pause_media" -> strings.get(language, R.string.done_pause)
            "next_track" -> strings.get(language, R.string.done_next)
            "previous_track" -> strings.get(language, R.string.done_previous)

            // Tools that report a real answer -- the time, the battery level,
            // what is on screen -- say it themselves, and paraphrasing would
            // throw the information away.
            "get_time", "get_battery_level", "get_location",
            "read_notifications", "read_screen", "get_volume",
            "lookup_contact", "list_installed_apps",
            -> result.summary

            else -> strings.get(language, R.string.done_generic)
        }
}
