package com.jarvis.assistant.ai

import com.jarvis.assistant.core.Language
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class LocalIntentMatcherTest {

    private val matcher = LocalIntentMatcher()

    private fun match(text: String) = matcher.match(text, Language.AUTO)

    // ------------------------------------------------------------ app launch

    @Test
    fun `English open command extracts the app name`() {
        val call = match("open YouTube")
        assertNotNull("expected a match for 'open YouTube'", call)
        assertEquals("open_app", call?.name)
        assertEquals("YouTube", call?.string("app"))
    }

    @Test
    fun `Vietnamese open command extracts the app name`() {
        val call = match("mở YouTube")
        assertEquals("open_app", call?.name)
        assertEquals("YouTube", call?.string("app"))
    }

    @Test
    fun `Vietnamese open command without diacritics still matches`() {
        val call = match("mo Zalo")
        assertEquals("open_app", call?.name)
        assertEquals("Zalo", call?.string("app"))
    }

    // ------------------------------------------------------------ device

    @Test
    fun `volume up in both languages`() {
        assertEquals("increase_volume", match("volume up")?.name)
        assertEquals("increase_volume", match("tăng âm lượng")?.name)
    }

    @Test
    fun `volume down in both languages`() {
        assertEquals("decrease_volume", match("turn the volume down")?.name)
        assertEquals("decrease_volume", match("giảm âm lượng")?.name)
    }

    @Test
    fun `set volume captures the percentage`() {
        val call = match("set volume to 40")
        assertEquals("set_volume", call?.name)
        assertEquals(40, call?.int("percent"))
    }

    @Test
    fun `go home in both languages`() {
        assertEquals("go_home", match("go home")?.name)
        assertEquals("go_home", match("về màn hình chính")?.name)
    }

    @Test
    fun `go back in both languages`() {
        assertEquals("go_back", match("go back")?.name)
        assertEquals("go_back", match("quay lại")?.name)
    }

    @Test
    fun `flashlight on carries the on argument`() {
        val call = match("bật đèn pin")
        assertEquals("toggle_flashlight", call?.name)
        assertEquals(true, call?.boolean("on"))
    }

    @Test
    fun `flashlight off carries the off argument`() {
        val call = match("turn off the flashlight")
        assertEquals("toggle_flashlight", call?.name)
        assertEquals(false, call?.boolean("on"))
    }

    @Test
    fun `screenshot in both languages`() {
        assertEquals("take_screenshot", match("take a screenshot")?.name)
        assertEquals("take_screenshot", match("chụp màn hình")?.name)
    }

    @Test
    fun `scroll captures direction and repeat count`() {
        val call = match("scroll down 5 times")
        assertEquals("scroll", call?.name)
        assertEquals("down", call?.string("direction"))
        assertEquals(5, call?.int("times"))
    }

    @Test
    fun `Vietnamese scroll captures the repeat count`() {
        val call = match("cuộn xuống 3 lần")
        assertEquals("scroll", call?.name)
        assertEquals("down", call?.string("direction"))
        assertEquals(3, call?.int("times"))
    }

    @Test
    fun `media controls match`() {
        assertEquals("pause_media", match("pause")?.name)
        assertEquals("next_track", match("bài tiếp theo")?.name)
    }

    // ------------------------------------------------- deliberate non-matches

    @Test
    fun `a multi step command is left to the model`() {
        // This is the important one. The fast path must not half-handle a
        // compound instruction by opening YouTube and silently dropping the
        // search.
        assertNull(match("Open YouTube and search for SCADA IEC 104 tutorial"))
    }

    @Test
    fun `a Vietnamese multi step command is left to the model`() {
        assertNull(match("mở Chrome rồi tìm thời tiết Cao Bằng"))
    }

    @Test
    fun `a question is left to the model`() {
        assertNull(match("what is the weather in Cao Bang"))
    }

    @Test
    fun `an empty utterance matches nothing`() {
        assertNull(match("   "))
    }
}
