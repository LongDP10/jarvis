package com.jarvis.assistant.commands

import com.jarvis.assistant.core.ConfirmationRequest
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.core.JarvisStateMachine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Stands between the model and anything irreversible.
 *
 * Every tool marked dangerous goes through here, and there is no path around it:
 * [CommandExecutor] consults the tool's own spec, not anything the model sent,
 * so a model cannot claim a call is pre-approved. The gate suspends the
 * executing coroutine until a human answers, or until the request times out, in
 * which case the answer is no.
 */
@Singleton
class ConfirmationGate @Inject constructor(
    private val stateMachine: JarvisStateMachine,
) {

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    suspend fun request(request: ConfirmationRequest): Boolean {
        val deferred = CompletableDeferred<Boolean>()
        pending[request.id] = deferred
        stateMachine.transition(JarvisState.ConfirmationRequired(request))

        val approved = withTimeoutOrNull(TIMEOUT_MS) { deferred.await() } ?: false
        pending.remove(request.id)

        // Whoever asked is responsible for the next state; clearing the dialog
        // state here stops a stale prompt lingering after a timeout.
        val current = stateMachine.state.value
        if (current is JarvisState.ConfirmationRequired && current.request.id == request.id) {
            stateMachine.transition(JarvisState.Processing())
        }
        return approved
    }

    /** Called by the UI. An unknown id is ignored, which handles stale dialogs. */
    fun resolve(requestId: String, approved: Boolean) {
        pending.remove(requestId)?.complete(approved)
    }

    fun cancelAll() {
        pending.values.forEach { it.complete(false) }
        pending.clear()
    }

    private companion object {
        /**
         * Long enough for someone to read the prompt and decide, short enough
         * that a forgotten dialog does not hold a coroutine open indefinitely.
         */
        const val TIMEOUT_MS = 120_000L
    }
}
