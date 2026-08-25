package com.jarvis.assistant.commands

import android.content.Intent
import kotlinx.serialization.json.JsonObject

/**
 * What happened when a tool ran.
 *
 * The distinction between [Failure] and [NotSupported] is the heart of the
 * app's honesty rule. "Failure" means JARVIS tried and it did not work.
 * "NotSupported" means Android forbids the action outright, and JARVIS must say
 * so rather than pretending -- optionally after opening the screen where the
 * user can do it themselves in one tap.
 *
 * No variant may ever be constructed to describe something that did not
 * actually happen.
 */
sealed interface ToolResult {

    /** Text handed to the model and, usually, spoken to the user. */
    val summary: String

    data class Success(
        override val summary: String,
        val data: JsonObject? = null,
    ) : ToolResult

    data class Failure(
        override val summary: String,
    ) : ToolResult

    data class RequiresPermission(
        val permission: String,
        val rationale: String,
        override val summary: String = rationale,
    ) : ToolResult

    /**
     * @param fallbackIntent a screen that lets the user complete the action
     *   themselves. Launched by the executor before the summary is spoken, so by
     *   the time JARVIS explains the limitation the right screen is already up.
     */
    data class NotSupported(
        override val summary: String,
        val fallbackIntent: Intent? = null,
    ) : ToolResult

    /** The user declined at the confirmation dialog. Not an error. */
    data class Cancelled(
        override val summary: String,
    ) : ToolResult

    val isSuccess: Boolean get() = this is Success
}
