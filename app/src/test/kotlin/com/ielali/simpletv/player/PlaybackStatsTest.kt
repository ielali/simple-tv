package com.ielali.simpletv.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaybackStatsTest {

    private var now = 1_000L
    private val stats = PlaybackStats { now }

    private fun after(ms: Long, state: PlaybackState): PlaybackStats.Event? {
        now += ms
        return stats.onState(state)
    }

    @Test
    fun `first picture is timed from the tune`() {
        assertNull(stats.onTune())
        assertNull(after(500, PlaybackState.TUNING))
        assertEquals(PlaybackStats.Event.FirstPicture(3_200), after(2_700, PlaybackState.PLAYING))
    }

    @Test
    fun `a stall is timed and says how long the picture had played`() {
        stats.onTune()
        after(3_000, PlaybackState.PLAYING)
        assertNull(after(24_000, PlaybackState.REBUFFERING))
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 3_600, playedMs = 24_000), after(3_600, PlaybackState.PLAYING))
        after(8_000, PlaybackState.REBUFFERING)
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 11_000, playedMs = 8_000), after(11_000, PlaybackState.PLAYING))
    }

    @Test
    fun `leaving a channel sums it up`() {
        stats.onTune()
        after(3_000, PlaybackState.PLAYING)
        after(24_000, PlaybackState.REBUFFERING)
        after(3_600, PlaybackState.PLAYING)
        after(8_000, PlaybackState.REBUFFERING)
        after(11_000, PlaybackState.PLAYING)
        now += 10_000
        assertEquals(
            PlaybackStats.Summary(firstPictureMs = 3_000, playedMs = 42_000, stalls = 2, stalledMs = 14_600, longestStallMs = 11_000, errors = 0),
            stats.onTune(),
        )
    }

    @Test
    fun `a failure after the picture counts as a freeze until the picture is back`() {
        stats.onTune()
        after(2_000, PlaybackState.PLAYING)
        after(5_000, PlaybackState.REBUFFERING)
        // The stall turns into an error and the engine retries: still one freeze.
        assertNull(after(8_000, PlaybackState.ERROR))
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 12_000, playedMs = 5_000), after(4_000, PlaybackState.PLAYING))
        assertEquals(
            PlaybackStats.Summary(firstPictureMs = 2_000, playedMs = 5_000, stalls = 1, stalledMs = 12_000, longestStallMs = 12_000, errors = 1),
            stats.onStop(),
        )
    }

    @Test
    fun `a channel that never plays has no first picture`() {
        stats.onTune()
        after(8_000, PlaybackState.ERROR)
        after(3_000, PlaybackState.ERROR)
        now += 4_000
        assertEquals(
            PlaybackStats.Summary(firstPictureMs = null, playedMs = 0, stalls = 0, stalledMs = 0, longestStallMs = 0, errors = 1),
            stats.onTune(),
        )
    }

    @Test
    fun `no signal before the picture is not a stall`() {
        stats.onTune()
        after(8_000, PlaybackState.ERROR)
        assertEquals(PlaybackStats.Event.FirstPicture(11_000), after(3_000, PlaybackState.PLAYING))
        assertEquals(0, stats.onStop()!!.stalls)
    }

    @Test
    fun `leaving while frozen counts the freeze so far`() {
        stats.onTune()
        after(2_000, PlaybackState.PLAYING)
        after(10_000, PlaybackState.REBUFFERING)
        now += 6_000
        assertEquals(
            PlaybackStats.Summary(firstPictureMs = 2_000, playedMs = 10_000, stalls = 1, stalledMs = 6_000, longestStallMs = 6_000, errors = 0),
            stats.onTune(),
        )
    }

    @Test
    fun `a film that ends is not a stall`() {
        stats.onTune()
        after(2_000, PlaybackState.PLAYING)
        assertNull(after(60_000, PlaybackState.IDLE))
        assertEquals(
            PlaybackStats.Summary(firstPictureMs = 2_000, playedMs = 60_000, stalls = 0, stalledMs = 0, longestStallMs = 0, errors = 0),
            stats.onStop(),
        )
    }

    @Test
    fun `a stream that ended and is loading again counts as a freeze`() {
        stats.onTune()
        after(2_000, PlaybackState.PLAYING)
        assertNull(after(30_000, PlaybackState.IDLE))
        assertNull(after(3_000, PlaybackState.REBUFFERING))
        assertEquals(PlaybackStats.Event.StallEnded(lastedMs = 4_000, playedMs = 30_000), after(4_000, PlaybackState.PLAYING))
    }

    @Test
    fun `nothing is reported before a tune or after a stop`() {
        assertNull(stats.onState(PlaybackState.PLAYING))
        assertNull(stats.onStop())
        stats.onTune()
        after(1_000, PlaybackState.PLAYING)
        stats.onStop()
        assertNull(after(1_000, PlaybackState.REBUFFERING))
        assertNull(stats.onStop())
    }

    @Test
    fun `each channel starts from zero`() {
        stats.onTune()
        after(2_000, PlaybackState.PLAYING)
        after(5_000, PlaybackState.REBUFFERING)
        after(1_000, PlaybackState.PLAYING)
        stats.onTune()
        assertEquals(PlaybackStats.Event.FirstPicture(1_500), after(1_500, PlaybackState.PLAYING))
        now += 3_000
        assertEquals(
            PlaybackStats.Summary(firstPictureMs = 1_500, playedMs = 3_000, stalls = 0, stalledMs = 0, longestStallMs = 0, errors = 0),
            stats.onStop(),
        )
    }

    @Test
    fun `log lines use plain seconds whatever the TV language`() {
        val saved = java.util.Locale.getDefault()
        java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar-EG"))
        try {
            assertEquals("first picture after 3.4 s", PlaybackStats.Event.FirstPicture(3_400).line())
            assertEquals(
                "picture froze for 11.0 s after playing 8.0 s",
                PlaybackStats.Event.StallEnded(lastedMs = 11_000, playedMs = 8_040).line(),
            )
            assertEquals(
                "first picture after 3.4 s, played 65.0 s, stalls 3, frozen 31.7 s (longest 17.1 s), errors 0",
                PlaybackStats.Summary(3_400, 65_000, 3, 31_700, 17_100, 0).line(),
            )
            assertEquals(
                "no picture, played 0.0 s, stalls 0, frozen 0.0 s (longest 0.0 s), errors 2",
                PlaybackStats.Summary(null, 0, 0, 0, 0, 2).line(),
            )
        } finally {
            java.util.Locale.setDefault(saved)
        }
    }
}
