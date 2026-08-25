package com.jarvis.assistant.voice

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow

/**
 * Listens for the wake phrase in the background.
 *
 * An interface rather than a concrete class because the default implementation
 * makes a real trade-off: it reuses Android's recogniser, which costs nothing to
 * ship and works offline with a language pack, but keeps a recognition session
 * cycling and therefore uses meaningfully more battery than a dedicated
 * always-on keyword spotter. Anyone who would rather have a Picovoice
 * AccessKey and a .ppn keyword file can drop that in behind this interface
 * without touching the rest of the app.
 */
interface WakeWordEngine {

    val isRunning: StateFlow<Boolean>

    /**
     * Starts listening. [onDetected] is invoked on [scope]'s dispatcher each
     * time the wake phrase is heard; the engine keeps running afterwards so a
     * second command does not need the toggle flipped again.
     */
    fun start(scope: CoroutineScope, onDetected: () -> Unit)

    /**
     * Releases the microphone without forgetting that it should be running.
     * Used while JARVIS is handling a command, because two recognition sessions
     * competing for the microphone means neither one hears anything.
     */
    fun pause()

    fun resume()

    fun stop()
}
