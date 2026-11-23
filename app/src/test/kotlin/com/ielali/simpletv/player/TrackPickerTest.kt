package com.ielali.simpletv.player

import com.ielali.simpletv.player.TrackPicker.Choice
import com.ielali.simpletv.player.TrackPicker.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TrackPickerTest {

    private val subs = listOf(
        Track(2, 0, "eng", isDefault = true),
        Track(3, 0, "ara", isForced = true),
        Track(4, 0, "ar"),
        Track(5, 0, null),
    )

    @Test
    fun `language codes are normalised`() {
        assertEquals("ar", TrackPicker.normalise("ara"))
        assertEquals("ar", TrackPicker.normalise("ar-SA"))
        assertEquals("en", TrackPicker.normalise("ENG"))
        assertEquals("en", TrackPicker.normalise("en_GB"))
        assertNull(TrackPicker.normalise("und"))
        assertNull(TrackPicker.normalise(" "))
    }

    @Test
    fun `no wish turns subtitles off`() {
        assertEquals(Choice.Off, TrackPicker.subtitles(subs, null))
        assertEquals(Choice.Off, TrackPicker.subtitles(subs, ""))
    }

    @Test
    fun `wished language picks full subtitles over forced ones`() {
        assertEquals(Choice.Pick(4, 0), TrackPicker.subtitles(subs, "ar"))
        assertEquals(Choice.Pick(2, 0), TrackPicker.subtitles(subs, "en"))
    }

    @Test
    fun `missing language plays without subtitles`() {
        assertEquals(Choice.Off, TrackPicker.subtitles(subs, "fr"))
        assertEquals(Choice.Off, TrackPicker.subtitles(emptyList(), "ar"))
    }

    @Test
    fun `any language prefers the file's default full subtitles`() {
        assertEquals(Choice.Pick(2, 0), TrackPicker.subtitles(subs, TrackPicker.ANY))
        assertEquals(Choice.Pick(5, 0), TrackPicker.subtitles(listOf(Track(3, 0, "ar", isForced = true), Track(5, 0, null)), TrackPicker.ANY))
        assertEquals(Choice.Off, TrackPicker.subtitles(emptyList(), TrackPicker.ANY))
    }

    @Test
    fun `soundtrack follows the wish and otherwise keeps the file's own`() {
        val audio = listOf(Track(0, 0, "eng", isDefault = true), Track(1, 0, "ara"), Track(1, 1, "ara", isDefault = true))
        assertEquals(Choice.Pick(1, 1), TrackPicker.audio(audio, "ar"))
        assertEquals(Choice.Keep, TrackPicker.audio(audio, "fr"))
        assertEquals(Choice.Keep, TrackPicker.audio(audio, null))
        assertEquals(listOf("en", "ar"), TrackPicker.languages(audio))
    }
}
