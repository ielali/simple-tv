package com.ielali.simpletv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStatsTest {

    private var now = 1_000L
    private val stats = PlaybackStats({ now })

    private fun after(ms: Long, state: PlaybackState): PlaybackStats.Event? {
        now += ms
        return stats.onState(state)
    }

    /** The usual start with the screen on: a frame is drawn, and the tuning card goes at the same moment. */
    private fun picture(ms: Long): PlaybackStats.Event? {
        now += ms
        stats.onFirstFrame()
        return stats.onState(PlaybackState.PLAYING)
    }

    private fun summary(
        firstPictureMs: Long?, playedMs: Long, stalls: Int = 0, stalledMs: Long = 0, longestStallMs: Long = 0,
        errors: Int = 0, firstFrameMs: Long? = firstPictureMs, readyMs: Long? = firstPictureMs,
    ) = PlaybackStats.Summary(firstFrameMs, readyMs, firstPictureMs, playedMs, stalls, stalledMs, longestStallMs, errors)

    @Test
    fun `first picture is timed from the tune`() {
        assertNull(stats.onTune())
        assertNull(after(500, PlaybackState.TUNING))
        now += 2_400
        // The frame is drawn under the tuning card; the card goes when the start buffer has filled.
        assertEquals(PlaybackStats.Event.FirstFrame(2_900), stats.onFirstFrame())
        assertEquals(PlaybackStats.Event.FirstPicture(3_200), after(300, PlaybackState.PLAYING))
    }

    @Test
    fun `the first frame is reported once per channel`() {
        assertNull(stats.onFirstFrame())
        stats.onTune()
        now += 2_900
        assertEquals(PlaybackStats.Event.FirstFrame(2_900), stats.onFirstFrame())
        after(300, PlaybackState.PLAYING)
        // Drawn again after the video view moved: not a second first frame.
        now += 5_000
        assertNull(stats.onFirstFrame())
        assertEquals(summary(firstPictureMs = 3_200, playedMs = 5_000, firstFrameMs = 2_900), stats.onTune())
        now += 1_000
        assertEquals(PlaybackStats.Event.FirstFrame(1_000), stats.onFirstFrame())
    }

    @Test
    fun `ready with nothing drawn is not a picture`() {
        // The screen is off (or the channel has sound only): the player gets ready and draws nothing.
        stats.onTune()
        assertEquals(PlaybackStats.Event.ReadyWithoutPicture(3_300), after(3_300, PlaybackState.PLAYING))
        now += 34_900
        assertEquals(summary(firstPictureMs = null, playedMs = 34_900, firstFrameMs = null, readyMs = 3_300), stats.onTune())
    }

    @Test
    fun `the picture arrives with the frame when the screen comes on later`() {
        stats.onTune()
        after(3_000, PlaybackState.PLAYING)
        now += 60_000
        assertEquals(PlaybackStats.Event.FirstPicture(63_000), stats.onFirstFrame())
        now += 5_000
        assertEquals(summary(firstPictureMs = 63_000, playedMs = 65_000, firstFrameMs = 63_000, readyMs = 3_000), stats.onStop())
    }

    @Test
    fun `a stall is timed and says how long the picture had played`() {
        stats.onTune()
        picture(3_000)
        assertNull(after(24_000, PlaybackState.REBUFFERING))
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 3_600, playedMs = 24_000), after(3_600, PlaybackState.PLAYING))
        after(8_000, PlaybackState.REBUFFERING)
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 11_000, playedMs = 8_000), after(11_000, PlaybackState.PLAYING))
    }

    @Test
    fun `a stall counts without a picture too`() {
        stats.onTune()
        after(2_000, PlaybackState.PLAYING)
        after(10_000, PlaybackState.REBUFFERING)
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 5_000, playedMs = 10_000), after(5_000, PlaybackState.PLAYING))
    }

    @Test
    fun `leaving a channel sums it up`() {
        stats.onTune()
        picture(3_000)
        after(24_000, PlaybackState.REBUFFERING)
        after(3_600, PlaybackState.PLAYING)
        after(8_000, PlaybackState.REBUFFERING)
        after(11_000, PlaybackState.PLAYING)
        now += 10_000
        assertEquals(
            summary(firstPictureMs = 3_000, playedMs = 42_000, stalls = 2, stalledMs = 14_600, longestStallMs = 11_000),
            stats.onTune(),
        )
    }

    @Test
    fun `a failure after the picture counts as a freeze until the picture is back`() {
        stats.onTune()
        picture(2_000)
        after(5_000, PlaybackState.REBUFFERING)
        // The stall turns into an error and the engine retries: still one freeze.
        assertNull(after(8_000, PlaybackState.ERROR))
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 12_000, playedMs = 5_000), after(4_000, PlaybackState.PLAYING))
        assertEquals(
            summary(firstPictureMs = 2_000, playedMs = 5_000, stalls = 1, stalledMs = 12_000, longestStallMs = 12_000, errors = 1),
            stats.onStop(),
        )
    }

    @Test
    fun `a channel that never plays has no first picture`() {
        stats.onTune()
        after(8_000, PlaybackState.ERROR)
        after(3_000, PlaybackState.ERROR)
        now += 4_000
        assertEquals(summary(firstPictureMs = null, playedMs = 0, errors = 1), stats.onTune())
    }

    @Test
    fun `no signal before the picture is not a stall`() {
        stats.onTune()
        after(8_000, PlaybackState.ERROR)
        assertEquals(PlaybackStats.Event.FirstPicture(11_000), picture(3_000))
        assertEquals(0, stats.onStop()!!.stalls)
    }

    @Test
    fun `leaving while frozen counts the freeze so far`() {
        stats.onTune()
        picture(2_000)
        after(10_000, PlaybackState.REBUFFERING)
        now += 6_000
        assertEquals(
            summary(firstPictureMs = 2_000, playedMs = 10_000, stalls = 1, stalledMs = 6_000, longestStallMs = 6_000),
            stats.onTune(),
        )
    }

    @Test
    fun `a film that ends is not a stall`() {
        stats.onTune()
        picture(2_000)
        assertNull(after(60_000, PlaybackState.IDLE))
        assertEquals(summary(firstPictureMs = 2_000, playedMs = 60_000), stats.onStop())
    }

    @Test
    fun `a stream that ended and is loading again counts as a freeze`() {
        stats.onTune()
        picture(2_000)
        assertNull(after(30_000, PlaybackState.IDLE))
        assertNull(after(3_000, PlaybackState.REBUFFERING))
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 4_000, playedMs = 30_000), after(4_000, PlaybackState.PLAYING))
    }

    @Test
    fun `a blink shorter than a quarter of a second is not a freeze`() {
        stats.onTune()
        picture(2_000)
        after(8_000, PlaybackState.REBUFFERING)
        assertNull(after(14, PlaybackState.PLAYING))
        now += 5_000
        assertEquals(summary(firstPictureMs = 2_000, playedMs = 13_000), stats.onStop())
    }

    @Test
    fun `paused time is not played time`() {
        stats.onTune()
        picture(2_000)
        now += 10_000
        // The TV goes to standby for an hour (the player stays ready), or a film is paused with OK.
        stats.onPaused(true)
        stats.onPaused(true)
        now += 3_600_000
        stats.onPaused(false)
        stats.onPaused(false)
        now += 5_000
        assertEquals(summary(firstPictureMs = 2_000, playedMs = 15_000), stats.onStop())
    }

    @Test
    fun `a freeze during standby counts only from when the screen is back`() {
        stats.onTune()
        picture(2_000)
        now += 10_000
        stats.onPaused(true)
        assertNull(after(100_000, PlaybackState.REBUFFERING))
        now += 50_000
        stats.onPaused(false)
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 4_000, playedMs = 10_000), after(4_000, PlaybackState.PLAYING))
    }

    @Test
    fun `a stream that recovers during standby did not freeze`() {
        stats.onTune()
        picture(2_000)
        now += 10_000
        stats.onPaused(true)
        after(60_000, PlaybackState.REBUFFERING)
        assertNull(after(5_000, PlaybackState.PLAYING))
        now += 60_000
        stats.onPaused(false)
        now += 3_000
        assertEquals(summary(firstPictureMs = 2_000, playedMs = 13_000), stats.onStop())
    }

    @Test
    fun `the wait for the first picture leaves out paused time`() {
        stats.onTune()
        now += 1_000
        stats.onPaused(true)
        now += 60_000
        stats.onPaused(false)
        assertEquals(PlaybackStats.Event.FirstPicture(3_000), picture(2_000))
    }

    @Test
    fun `a channel chosen while paused is timed from when playback resumes`() {
        stats.onPaused(true)
        now += 30_000
        stats.onTune()
        now += 500
        stats.onPaused(false)
        assertEquals(PlaybackStats.Event.FirstPicture(2_000), picture(2_000))
    }

    @Test
    fun `the screen going off ends the stay and the channel is timed again from when it is back`() {
        stats.onTune()
        picture(2_000)
        now += 10_000
        // Standby: the activity pauses the player, then the live channel lets go of its stream.
        stats.onPaused(true)
        now += 300
        assertEquals(summary(firstPictureMs = 2_000, playedMs = 10_000), stats.onStop())
        now += 21_000_000
        // Back on: the engine tunes again and then tells the player to play.
        assertNull(stats.onTune())
        stats.onPaused(false)
        now += 2_800
        assertEquals(PlaybackStats.Event.FirstFrame(2_800), stats.onFirstFrame())
        assertEquals(PlaybackStats.Event.FirstPicture(3_000), after(200, PlaybackState.PLAYING))
    }

    @Test
    fun `nothing is reported before a tune or after a stop`() {
        assertNull(stats.onState(PlaybackState.PLAYING))
        assertNull(stats.onStop())
        stats.onTune()
        picture(1_000)
        stats.onStop()
        assertNull(after(1_000, PlaybackState.REBUFFERING))
        assertNull(stats.onFirstFrame())
        assertNull(stats.onStop())
    }

    @Test
    fun `each channel starts from zero`() {
        stats.onTune()
        picture(2_000)
        after(5_000, PlaybackState.REBUFFERING)
        after(1_000, PlaybackState.PLAYING)
        stats.onTune()
        assertEquals(PlaybackStats.Event.FirstPicture(1_500), picture(1_500))
        now += 3_000
        assertEquals(summary(firstPictureMs = 1_500, playedMs = 3_000), stats.onStop())
    }

    @Test
    fun `log lines use plain seconds whatever the TV language`() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
        try {
            assertEquals("first frame drawn after 3.1 s", PlaybackStats.Event.FirstFrame(3_100).line())
            assertEquals("first picture after 3.4 s", PlaybackStats.Event.FirstPicture(3_400).line())
            assertEquals(
                "ready after 3.3 s, no frame drawn (screen off or sound only)",
                PlaybackStats.Event.ReadyWithoutPicture(3_300).line(),
            )
            assertEquals(
                "picture froze for 11.0 s after playing 8.0 s",
                PlaybackStats.Event.StallEnded(lastedMs = 11_000, playedMs = 8_040).line(),
            )
            assertEquals(
                "first frame after 3.1 s, first picture after 3.4 s, played 65.0 s, stalls 3, frozen 31.7 s (longest 17.1 s), errors 0",
                summary(3_400, 65_000, 3, 31_700, 17_100, 0, firstFrameMs = 3_100).line(),
            )
            assertEquals(
                "no picture (ready after 3.3 s, no frame drawn), played 34.9 s, stalls 0, frozen 0.0 s (longest 0.0 s), errors 0",
                summary(null, 34_900, firstFrameMs = null, readyMs = 3_300).line(),
            )
            assertEquals(
                "no picture (never ready), played 0.0 s, stalls 0, frozen 0.0 s (longest 0.0 s), errors 2",
                summary(null, 0, errors = 2).line(),
            )
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
