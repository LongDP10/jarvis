package com.jarvis.assistant.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val JarvisColorScheme = darkColorScheme(
    primary = JarvisColors.Primary,
    onPrimary = JarvisColors.OnPrimary,
    primaryContainer = JarvisColors.PrimaryDim,
    onPrimaryContainer = JarvisColors.OnBackground,
    secondary = JarvisColors.Secondary,
    onSecondary = JarvisColors.OnPrimary,
    tertiary = JarvisColors.Tertiary,
    background = JarvisColors.Background,
    onBackground = JarvisColors.OnBackground,
    surface = JarvisColors.Surface,
    onSurface = JarvisColors.OnBackground,
    surfaceVariant = JarvisColors.SurfaceElevated,
    onSurfaceVariant = JarvisColors.OnSurfaceMuted,
    outline = JarvisColors.Outline,
    error = JarvisColors.Error,
)

/**
 * Deliberately ignores [isSystemInDarkTheme] and dynamic colour. A holographic
 * assistant that turns beige when the phone switches to light mode, or purple
 * because the wallpaper is purple, stops reading as the same product.
 */
@Composable
fun JarvisTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = JarvisColorScheme,
        typography = JarvisTypography,
        content = content,
    )
}
