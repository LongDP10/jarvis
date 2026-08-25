package com.jarvis.assistant.core

import android.util.Log
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The single source of truth for what JARVIS is doing.
 *
 * Both the activity UI and the overlay service observe this same instance, which
 * is the only reason the in-app orb and the floating orb can never disagree. It
 * holds no coroutine scope of its own: callers own their own lifecycles, this
 * just holds the value.
 */
@Singleton
class JarvisStateMachine @Inject constructor() {

    private val _state = MutableStateFlow<JarvisState>(JarvisState.Idle)
    val state: StateFlow<JarvisState> = _state.asStateFlow()

    /**
     * Transitions, for the debug console. Replay is kept small: this is a live
     * trace, not a log store -- [com.jarvis.assistant.data.repo.CommandLogRepository]
     * is where anything durable goes.
     */
    private val _transitions = MutableSharedFlow<JarvisState>(replay = 16, extraBufferCapacity = 64)
    val transitions: SharedFlow<JarvisState> = _transitions.asSharedFlow()

    fun transition(to: JarvisState) {
        val from = _state.value
        if (from == to) return
        _state.value = to
        _transitions.tryEmit(to)
        if (BuildFlags.VERBOSE_STATE) {
            Log.d(TAG, "${from.label()} -> ${to.label()}")
        }
    }

    /** Cheap update that will not fight a newer state written from elsewhere. */
    fun updateListening(partial: String, amplitude: Float) {
        _state.update { current ->
            if (current is JarvisState.Listening) {
                current.copy(partial = partial, amplitude = amplitude)
            } else {
                current
            }
        }
    }

    fun updateSpeaking(progress: Float) {
        _state.update { current ->
            if (current is JarvisState.Speaking) current.copy(progress = progress) else current
        }
    }

    fun reset() = transition(JarvisState.Idle)

    /**
     * Errors settle to [JarvisState.Error] so the UI can show them, but callers
     * are expected to reset once the message has been spoken or displayed.
     */
    fun fail(message: String) = transition(JarvisState.Error(message))

    private fun JarvisState.label(): String = when (this) {
        is JarvisState.Idle -> "IDLE"
        is JarvisState.Wake -> "WAKE"
        is JarvisState.Listening -> "LISTENING"
        is JarvisState.Processing -> "PROCESSING"
        is JarvisState.Executing -> "EXECUTING($step/$total)"
        is JarvisState.Speaking -> "SPEAKING"
        is JarvisState.ConfirmationRequired -> "CONFIRMATION_REQUIRED"
        is JarvisState.WaitingForUser -> "WAITING_FOR_USER"
        is JarvisState.Error -> "ERROR"
        is JarvisState.Cancelled -> "CANCELLED"
    }

    private companion object {
        const val TAG = "JarvisState"
    }
}

/** Compile-time switches that should never ship enabled. */
object BuildFlags {
    const val VERBOSE_STATE = false
}
