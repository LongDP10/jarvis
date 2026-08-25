package com.jarvis.assistant.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * JARVIS is a dark-only product. A holographic orb on a light background looks
 * wrong at every size, so rather than ship a washed-out light palette the app
 * commits to one deep, low-luminance surface set and spends its contrast budget
 * on the cyan accents.
 */
object JarvisColors {
    val Background = Color(0xFF050A12)
    val Surface = Color(0xFF0B1220)
    val SurfaceElevated = Color(0xFF131C2E)
    val SurfaceGlass = Color(0x1AFFFFFF)
    val Outline = Color(0x334F6B8F)

    val Primary = Color(0xFF67E8FF)
    val PrimaryDim = Color(0xFF2C8FA8)
    val PrimaryGlow = Color(0x6667E8FF)
    val Secondary = Color(0xFF7C9CFF)
    val Tertiary = Color(0xFFA78BFA)

    val OnBackground = Color(0xFFE6F1FF)
    val OnSurfaceMuted = Color(0xFF8296B0)
    val OnPrimary = Color(0xFF04222B)

    val Success = Color(0xFF4ADE80)
    val Warning = Color(0xFFFBBF24)
    val Error = Color(0xFFFF6B6B)

    /** Per-state orb accents. Kept here so the orb and the UI never drift apart. */
    val StateIdle = Primary
    val StateListening = Color(0xFF4ADE80)
    val StateThinking = Tertiary
    val StateExecuting = Color(0xFFFBBF24)
    val StateSpeaking = Secondary
    val StateError = Error
}
