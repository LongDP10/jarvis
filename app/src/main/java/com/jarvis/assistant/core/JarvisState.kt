package com.jarvis.assistant.core

import java.util.UUID

/**
 * Everything the orb and the screens need in order to draw themselves. The
 * payloads are deliberately concrete -- amplitude, step counts, partial
 * transcripts -- because the animation is driven by real signals rather than by
 * a timer pretending to be busy.
 */
sealed interface JarvisState {

    data object Idle : JarvisState

    /** Wake word fired; the recogniser has not started yet. */
    data object Wake : JarvisState

    data class Listening(
        val partial: String = "",
        val amplitude: Float = 0f,
    ) : JarvisState

    data class Processing(
        val label: String? = null,
    ) : JarvisState

    data class Executing(
        val label: String,
        val step: Int = 1,
        val total: Int = 1,
    ) : JarvisState {
        val progress: Float get() = if (total <= 0) 0f else step.toFloat() / total
    }

    data class Speaking(
        val text: String,
        val progress: Float = 0f,
    ) : JarvisState

    data class ConfirmationRequired(
        val request: ConfirmationRequest,
    ) : JarvisState

    data class WaitingForUser(
        val question: String,
        val options: List<String> = emptyList(),
    ) : JarvisState

    data class Error(
        val message: String,
    ) : JarvisState

    data object Cancelled : JarvisState
}

/**
 * A dangerous tool asking permission to run. [id] is what the UI hands back so a
 * stale dialog cannot resolve a newer request.
 */
data class ConfirmationRequest(
    val id: String = UUID.randomUUID().toString(),
    val toolName: String,
    val title: String,
    val body: String,
    val confirmLabel: String,
)

/** True while JARVIS is doing something the user should not interrupt casually. */
val JarvisState.isBusy: Boolean
    get() = this is JarvisState.Processing ||
        this is JarvisState.Executing ||
        this is JarvisState.Speaking

/** True while the microphone is or is about to be open. */
val JarvisState.isCapturingAudio: Boolean
    get() = this is JarvisState.Listening || this is JarvisState.Wake
