package com.jarvis.assistant.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {

    private val installed = listOf(
        AppEntry("YouTube", "com.google.android.youtube"),
        AppEntry("YT Music", "com.google.android.apps.youtube.music"),
        AppEntry("Google Chrome", "com.android.chrome"),
        AppEntry("Zalo", "com.zing.zalo"),
        AppEntry("Máy ảnh", "com.samsung.android.camera"),
        AppEntry("Music Player", "com.example.musicplayer"),
        AppEntry("Messenger", "com.facebook.orca"),
        AppEntry("Facebook", "com.facebook.katana"),
    )

    @Test
    fun `exact label wins over a longer partial match`() {
        val ranked = AppMatcher.rank("youtube", installed)
        assertEquals("com.google.android.youtube", ranked.first().packageName)
    }

    @Test
    fun `matching ignores case`() {
        val ranked = AppMatcher.rank("ZALO", installed)
        assertEquals("com.zing.zalo", ranked.first().packageName)
    }

    @Test
    fun `matching ignores Vietnamese diacritics in both directions`() {
        // The recogniser hands back unaccented text far more often than not, so
        // "may anh" has to find the app whose real label is "Máy ảnh".
        val ranked = AppMatcher.rank("may anh", installed)
        assertEquals("com.samsung.android.camera", ranked.first().packageName)
    }

    @Test
    fun `a single word finds the app whose label contains it`() {
        val ranked = AppMatcher.rank("chrome", installed)
        assertEquals("com.android.chrome", ranked.first().packageName)
    }

    @Test
    fun `an ambiguous query returns every candidate so the caller can ask`() {
        val ranked = AppMatcher.rank("music", installed)
        assertTrue(
            "expected both music apps, got ${ranked.map { it.label }}",
            ranked.size >= 2,
        )
        assertTrue(ranked.any { it.packageName == "com.google.android.apps.youtube.music" })
        assertTrue(ranked.any { it.packageName == "com.example.musicplayer" })
    }

    @Test
    fun `an unknown app matches nothing rather than guessing`() {
        assertTrue(AppMatcher.rank("spotify", installed).isEmpty())
    }

    @Test
    fun `a blank query matches nothing`() {
        assertTrue(AppMatcher.rank("   ", installed).isEmpty())
    }

    @Test
    fun `package name is accepted as a query`() {
        // The model sometimes echoes a package name back from an earlier
        // read_screen result; treating it as a miss would be needlessly brittle.
        val ranked = AppMatcher.rank("com.facebook.orca", installed)
        assertEquals("com.facebook.orca", ranked.first().packageName)
    }
}
