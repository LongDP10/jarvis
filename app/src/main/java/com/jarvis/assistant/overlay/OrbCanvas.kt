package com.jarvis.assistant.overlay

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.jarvis.assistant.core.JarvisState
import kotlin.math.cos
import kotlin.math.sin

/**
 * The orb itself, drawn once and reused by both the home screen and the floating
 * overlay so the two can never look like different products.
 *
 * Everything is drawn with primitives on a single Canvas: no images, no shaders,
 * no per-frame allocation of paths. That keeps it cheap enough to run at 120Hz
 * on a Galaxy display while sitting on top of another app, which is the one hard
 * performance constraint this component has.
 */
@Composable
fun OrbCanvas(
    state: JarvisState,
    modifier: Modifier = Modifier,
    size: Dp = 180.dp,
) {
    val visuals = rememberOrbVisuals(state)

    Canvas(modifier = modifier.size(size)) {
        val centre = Offset(this.size.width / 2f, this.size.height / 2f)
        val radius = this.size.minDimension / 2f

        drawGlow(centre, radius, visuals)
        drawAmplitudeRing(centre, radius, visuals)
        drawOuterArcs(centre, radius, visuals)
        drawMiddleArcs(centre, radius, visuals)
        if (visuals.showProgress) drawProgressRing(centre, radius, visuals)
        drawTicks(centre, radius, visuals)
        drawCore(centre, radius, visuals)
    }
}

private fun DrawScope.drawGlow(centre: Offset, radius: Float, visuals: OrbVisuals) {
    // Radial gradient rather than a blurred circle: blur on Android is either
    // unavailable below API 31 or expensive, and this is indistinguishable.
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                visuals.accent.copy(alpha = 0.30f * visuals.glow),
                visuals.accent.copy(alpha = 0.10f * visuals.glow),
                Color.Transparent,
            ),
            center = centre,
            radius = radius,
        ),
        radius = radius,
        center = centre,
    )
}

/**
 * The ring that reacts to sound. This is the part that has to be honest: its
 * radius comes from the microphone amplitude, so when the room is silent it sits
 * still rather than performing enthusiasm nobody caused.
 */
private fun DrawScope.drawAmplitudeRing(centre: Offset, radius: Float, visuals: OrbVisuals) {
    val reactive = radius * (0.52f + visuals.amplitude * 0.34f)
    drawCircle(
        color = visuals.accent.copy(alpha = 0.10f + visuals.amplitude * 0.35f),
        radius = reactive,
        center = centre,
        style = Stroke(width = radius * 0.035f),
    )
    drawCircle(
        color = visuals.accent.copy(alpha = 0.05f + visuals.amplitude * 0.18f),
        radius = reactive * 1.16f,
        center = centre,
        style = Stroke(width = radius * 0.018f),
    )
}

private fun DrawScope.drawOuterArcs(centre: Offset, radius: Float, visuals: OrbVisuals) {
    val arcRadius = radius * 0.92f
    val stroke = Stroke(width = radius * 0.022f)
    rotate(visuals.rotation, centre) {
        listOf(0f to 78f, 120f to 52f, 210f to 96f).forEach { (start, sweep) ->
            drawArc(
                color = visuals.accent.copy(alpha = 0.55f),
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(centre.x - arcRadius, centre.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = stroke,
            )
        }
    }
}

private fun DrawScope.drawMiddleArcs(centre: Offset, radius: Float, visuals: OrbVisuals) {
    val arcRadius = radius * 0.74f
    val stroke = Stroke(width = radius * 0.014f)
    // Counter-rotating: two rings turning the same way read as one solid object,
    // two turning against each other read as a mechanism.
    rotate(visuals.counterRotation, centre) {
        listOf(30f to 110f, 190f to 70f).forEach { (start, sweep) ->
            drawArc(
                color = visuals.accent.copy(alpha = 0.35f),
                startAngle = start,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = Offset(centre.x - arcRadius, centre.y - arcRadius),
                size = Size(arcRadius * 2, arcRadius * 2),
                style = stroke,
            )
        }
    }
}

/** Fills clockwise as tool steps complete, so "3 of 5" is legible at a glance. */
private fun DrawScope.drawProgressRing(centre: Offset, radius: Float, visuals: OrbVisuals) {
    val ringRadius = radius * 0.83f
    drawArc(
        color = visuals.accent.copy(alpha = 0.18f),
        startAngle = -90f,
        sweepAngle = 360f,
        useCenter = false,
        topLeft = Offset(centre.x - ringRadius, centre.y - ringRadius),
        size = Size(ringRadius * 2, ringRadius * 2),
        style = Stroke(width = radius * 0.03f),
    )
    drawArc(
        color = visuals.accent,
        startAngle = -90f,
        sweepAngle = 360f * visuals.progress.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(centre.x - ringRadius, centre.y - ringRadius),
        size = Size(ringRadius * 2, ringRadius * 2),
        style = Stroke(width = radius * 0.03f),
    )
}

/** Small marks around the rim; the detail that makes it read as an instrument. */
private fun DrawScope.drawTicks(centre: Offset, radius: Float, visuals: OrbVisuals) {
    val count = 36
    val inner = radius * 0.62f
    val outer = radius * 0.67f
    rotate(-visuals.rotation * 0.5f, centre) {
        repeat(count) { index ->
            val angle = (index * 360f / count) * DEGREES_TO_RADIANS
            val emphasised = index % 3 == 0
            val length = if (emphasised) outer + radius * 0.03f else outer
            drawLine(
                color = visuals.accent.copy(alpha = if (emphasised) 0.42f else 0.16f),
                start = Offset(
                    centre.x + cos(angle) * inner,
                    centre.y + sin(angle) * inner,
                ),
                end = Offset(
                    centre.x + cos(angle) * length,
                    centre.y + sin(angle) * length,
                ),
                strokeWidth = radius * 0.008f,
            )
        }
    }
}

private fun DrawScope.drawCore(centre: Offset, radius: Float, visuals: OrbVisuals) {
    val coreRadius = radius * 0.30f * visuals.coreScale

    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                visuals.accent.copy(alpha = 0.95f),
                visuals.accent.copy(alpha = 0.55f),
            ),
            center = centre,
            radius = coreRadius,
        ),
        radius = coreRadius,
        center = centre,
    )

    // A dark inset ring keeps the bright centre from blooming into a flat dot.
    drawCircle(
        color = Color(0xFF04121F),
        radius = coreRadius * 0.62f,
        center = centre,
    )

    drawCircle(
        color = visuals.accent,
        radius = coreRadius * 0.34f * (0.85f + visuals.amplitude * 0.4f),
        center = centre,
    )
}

private const val DEGREES_TO_RADIANS = (Math.PI / 180f).toFloat()
