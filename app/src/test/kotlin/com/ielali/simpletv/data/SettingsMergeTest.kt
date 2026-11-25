package com.ielali.simpletv.data

import com.ielali.simpletv.guard.GuardKeys
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsMergeTest {

    private fun patch(text: String) = Json.parseToJsonElement(text) as JsonObject

    @Test
    fun `partial update changes only the fields sent`() {
        val current = AppSettings(youtubePlayback = YouTubePlayback.YOUTUBE_APP, guardBlockedKeys = listOf(1000))
        val merged = SettingsMerge.merge(current, patch("""{"guardEnabled": false}"""))
        assertEquals(AppSettings(YouTubePlayback.YOUTUBE_APP, guardEnabled = false, guardBlockedKeys = listOf(1000)), merged)
    }

    @Test
    fun `unknown fields are ignored`() {
        val merged = SettingsMerge.merge(AppSettings(), patch("""{"nope": 1, "youtubePlayback": "YOUTUBE_APP"}"""))
        assertEquals(YouTubePlayback.YOUTUBE_APP, merged.youtubePlayback)
    }

    @Test
    fun `blocked keys are sanitized`() {
        val merged = SettingsMerge.merge(
            AppSettings(),
            patch("""{"guardBlockedKeys": [${GuardKeys.VOLUME_UP}, 1000, 1000, ${GuardKeys.HOME}, 999]}"""),
        )
        assertEquals(listOf(999, 1000), merged.guardBlockedKeys)
    }

    @Test
    fun `button jobs are sanitized and a button with a job is not blocked`() {
        val merged = SettingsMerge.merge(
            AppSettings(),
            patch("""{"listButtons": [${GuardKeys.GUIDE}, ${GuardKeys.HOME}], "previousButtons": [${GuardKeys.GUIDE}, 1000], "guardBlockedKeys": [1000, 999]}"""),
        )
        assertEquals(listOf(GuardKeys.GUIDE), merged.listButtons)
        assertEquals(listOf(1000), merged.previousButtons)
        assertEquals(listOf(999), merged.guardBlockedKeys)
        assertEquals(listOf(GuardKeys.TV, GuardKeys.GUIDE, GuardKeys.FEATURED_APP_1), AppSettings().listButtons)
    }

    @Test
    fun `the clock is small until the page asks for large, and other changes leave it alone`() {
        assertEquals(ClockSize.SMALL, AppSettings().clockSize)
        val large = SettingsMerge.merge(AppSettings(), patch("""{"showClock": true, "clockSize": "LARGE"}"""))
        assertEquals(ClockSize.LARGE, large.clockSize)
        val hidden = SettingsMerge.merge(large, patch("""{"showClock": false}"""))
        assertEquals(false, hidden.showClock)
        assertEquals(ClockSize.LARGE, hidden.clockSize)
    }

    @Test
    fun `settings saved before the clock had a size still load`() {
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"showClock": false, "language": "ar"}""")
        assertEquals(AppSettings(showClock = false, language = "ar"), old)
    }

    @Test
    fun `the preview's length is the page's choice, with 0 for no limit`() {
        assertEquals(15, AppSettings().previewMinutes)
        assertEquals(60, SettingsMerge.merge(AppSettings(), patch("""{"previewMinutes": 60}""")).previewMinutes)
        assertEquals(0, SettingsMerge.merge(AppSettings(), patch("""{"previewMinutes": 0}""")).previewMinutes)
        assertEquals(0, SettingsMerge.merge(AppSettings(), patch("""{"previewMinutes": -5}""")).previewMinutes)
        assertEquals(240, SettingsMerge.merge(AppSettings(), patch("""{"previewMinutes": 100000}""")).previewMinutes)
        // Another change leaves it alone, and settings saved before it existed still load.
        assertEquals(0, SettingsMerge.merge(AppSettings(previewMinutes = 0), patch("""{"showClock": false}""")).previewMinutes)
        val old = Json { ignoreUnknownKeys = true }.decodeFromString(AppSettings.serializer(), """{"showClock": false}""")
        assertEquals(15, old.previewMinutes)
    }

    @Test
    fun `negative pause is clamped`() {
        assertEquals(0L, SettingsMerge.merge(AppSettings(), patch("""{"guardPausedUntil": -5}""")).guardPausedUntil)
    }

    @Test
    fun `language is kept when supported and dropped otherwise`() {
        assertEquals("ar", SettingsMerge.merge(AppSettings(), patch("""{"language": "ar"}""")).language)
        assertEquals("", SettingsMerge.merge(AppSettings(language = "ar"), patch("""{"language": "klingon"}""")).language)
        assertEquals("ar", SettingsMerge.merge(AppSettings(language = "ar"), patch("""{"guardEnabled": false}""")).language)
    }
}
