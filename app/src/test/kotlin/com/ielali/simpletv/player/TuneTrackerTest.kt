package com.ielali.simpletv.player

import org.junit.Assert.assertEquals
import org.junit.Test

class TuneTrackerTest {

    @Test
    fun `tuning lasts until the first picture`() {
        val t = TuneTracker()
        assertEquals(PlaybackState.TUNING, t.onTune())
        assertEquals(PlaybackState.TUNING, t.onPhase(PlayerPhase.IDLE))
        assertEquals(PlaybackState.TUNING, t.onPhase(PlayerPhase.BUFFERING))
        assertEquals(PlaybackState.PLAYING, t.onPhase(PlayerPhase.READY))
    }

    @Test
    fun `a stall after playing is rebuffering, not tuning`() {
        val t = TuneTracker()
        t.onTune(); t.onPhase(PlayerPhase.READY)
        assertEquals(PlaybackState.REBUFFERING, t.onPhase(PlayerPhase.BUFFERING))
        assertEquals(PlaybackState.PLAYING, t.onPhase(PlayerPhase.READY))
    }

    @Test
    fun `no signal stays through retries until the picture plays`() {
        val t = TuneTracker()
        t.onTune()
        assertEquals(PlaybackState.ERROR, t.onError())
        assertEquals(PlaybackState.ERROR, t.onPhase(PlayerPhase.IDLE))
        assertEquals(PlaybackState.ERROR, t.onPhase(PlayerPhase.BUFFERING))
        assertEquals(PlaybackState.PLAYING, t.onPhase(PlayerPhase.READY))
    }

    @Test
    fun `a new channel starts tuning again even after an error`() {
        val t = TuneTracker()
        t.onTune(); t.onError()
        assertEquals(PlaybackState.TUNING, t.onTune())
        assertEquals(PlaybackState.TUNING, t.onPhase(PlayerPhase.BUFFERING))
    }

    @Test
    fun `stop goes idle`() {
        val t = TuneTracker()
        t.onTune(); t.onError()
        assertEquals(PlaybackState.IDLE, t.onStop())
        assertEquals(PlaybackState.IDLE, t.onPhase(PlayerPhase.IDLE))
    }

    @Test
    fun `retries back off to a ceiling and reset`() {
        val b = RetryBackoff(initialMs = 3_000, maxMs = 30_000)
        assertEquals(listOf(3_000L, 6_000L, 12_000L, 24_000L, 30_000L, 30_000L), List(6) { b.nextDelayMs() })
        b.reset()
        assertEquals(3_000L, b.nextDelayMs())
    }
}
