package com.jarvis.assistant.overlay

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import com.jarvis.assistant.core.JarvisState
import com.jarvis.assistant.ui.theme.JarvisColors

/**
 * Everything the orb needs to draw one frame, derived from what JARVIS is
 * actually doing.
 */
data class OrbVisuals(
    val accent: Color,
    val rotation: Float,
    val counterRotation: Float,
    val amplitude: Float,
    val glow: Float,
    val coreScale: Float,
    val progress: Float,
    val showProgress: Boolean,
)

/**
 * Maps state to motion.
 *
 * The animation is driven by real signals wherever one exists -- microphone
 * amplitude while listening, utterance progress while speaking, completed steps
 * while executing -- rather than by a generic "busy" spinner. That is the
 * difference between an orb that looks alive and one that looks like a loading
 * indicator with extra rings.
 *
 * Idle motion is kept slow and low-contrast on purpose: this thing sits on top
 * of other apps, and a bright pulsing circle in the corner of a video would be
 * intolerable.
 */
@Composable
fun rememberOrbVisuals(state: JarvisState): OrbVisuals {
    val transition = rememberInfiniteTransition(label = "orb")

    val speed = when (state) {
        is JarvisState.Idle -> 26_000
        is JarvisState.Wake, is JarvisState.Listening -> 9_000
        is JarvisState.Processing -> 3_200
        is JarvisState.Executing -> 5_000
        is JarvisState.Speaking -> 11_000
        else -> 20_000
    }

    val rotation by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(speed, easing = LinearEasing)),
        label = "rotation",
    )

    val counterRotation by transition.animateFloat(
        initialValue = 360f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            tween((speed * 1.6f).toInt(), easing = LinearEasing),
        ),
        label = "counter",
    )

    val breathe by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "breathe",
    )

    val targetAmplitude = when (state) {
        is JarvisState.Listening -> state.amplitude
        // TTS gives no waveform, only how far through the text it is, so the
        // speaking pulse is synthesised from a steady breath rather than
        // pretending to follow the audio.
        is JarvisState.Speaking -> 0.35f + 0.25f * kotlin.math.sin(breathe * Math.PI.toFloat() * 4f)
        is JarvisState.Processing -> 0.18f
        else -> 0.06f
    }

    val amplitude by animateFloatAsState(
        targetValue = targetAmplitude.coerceIn(0f, 1f),
        animationSpec = spring(dampingRatio = 0.75f, stiffness = 420f),
        label = "amplitude",
    )

    val accent by animateColorAsState(
        targetValue = accentFor(state),
        animationSpec = tween(450),
        label = "accent",
    )

    val glow by animateFloatAsState(
        targetValue = when (state) {
            is JarvisState.Idle -> 0.32f
            is JarvisState.Error -> 0.75f
            is JarvisState.ConfirmationRequired -> 0.8f
            else -> 0.62f
        },
        animationSpec = tween(500),
        label = "glow",
    )

    val coreScale by animateFloatAsState(
        targetValue = when (state) {
            is JarvisState.Idle -> 0.86f
            is JarvisState.Listening -> 1.06f
            is JarvisState.Speaking -> 1.0f
            else -> 0.95f
        },
        animationSpec = spring(dampingRatio = 0.7f, stiffness = 300f),
        label = "coreScale",
    )

    val progress by animateFloatAsState(
        targetValue = (state as? JarvisState.Executing)?.progress ?: 0f,
        animationSpec = tween(350),
        label = "progress",
    )

    // Returned by value rather than as a State holder: every field above is
    // already backed by an animation state, so recomposition delivers fresh
    // numbers without another layer of indirection.
    return OrbVisuals(
        accent = accent,
        rotation = rotation,
        counterRotation = counterRotation,
        amplitude = amplitude,
        glow = glow,
        coreScale = coreScale,
        progress = progress,
        showProgress = state is JarvisState.Executing,
    )
}

fun accentFor(state: JarvisState): Color = when (state) {
    is JarvisState.Idle, is JarvisState.Wake -> JarvisColors.StateIdle
    is JarvisState.Listening -> JarvisColors.StateListening
    is JarvisState.Processing -> JarvisColors.StateThinking
    is JarvisState.Executing -> JarvisColors.StateExecuting
    is JarvisState.Speaking -> JarvisColors.StateSpeaking
    is JarvisState.Error -> JarvisColors.StateError
    is JarvisState.ConfirmationRequired -> JarvisColors.Warning
    is JarvisState.WaitingForUser -> JarvisColors.Warning
    is JarvisState.Cancelled -> JarvisColors.OnSurfaceMuted
}
