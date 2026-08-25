package com.jarvis.assistant.voice

import com.jarvis.assistant.core.Language
import org.junit.Assert.assertEquals
import org.junit.Test

class LanguageDetectorTest {

    private val detector = LanguageDetector()

    @Test
    fun `accented Vietnamese is detected`() {
        assertEquals(
            Language.VIETNAMESE,
            detector.detect("Mở YouTube và tìm kiếm hướng dẫn SCADA"),
        )
    }

    @Test
    fun `plain English is detected`() {
        assertEquals(
            Language.ENGLISH,
            detector.detect("Open YouTube and search for a SCADA tutorial"),
        )
    }

    @Test
    fun `Vietnamese typed without diacritics is still detected`() {
        // Android's recogniser sometimes returns unaccented text, and users type
        // this way constantly. Falling back to English here would make JARVIS
        // answer the wrong language for a large share of real input.
        assertEquals(
            Language.VIETNAMESE,
            detector.detect("mo ung dung dien thoai giup toi"),
        )
    }

    @Test
    fun `empty input yields no opinion`() {
        assertEquals(Language.AUTO, detector.detect(""))
    }

    @Test
    fun `a bare proper noun yields no opinion`() {
        // "YouTube" is not evidence of anything; the caller should fall back to
        // the configured language rather than guess.
        assertEquals(Language.AUTO, detector.detect("YouTube"))
    }

    @Test
    fun `a single accented word is enough`() {
        assertEquals(Language.VIETNAMESE, detector.detect("Đóng"))
    }

    @Test
    fun `English command words beat an incidental Vietnamese-looking token`() {
        assertEquals(
            Language.ENGLISH,
            detector.detect("please open the camera and take a photo"),
        )
    }
}
