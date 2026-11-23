package com.ielali.simpletv.player

/** What the viewer should see for the current stream. */
enum class PlaybackState {
    IDLE,
    /** Channel just chosen, no picture yet: show the tuning card with the channel name. */
    TUNING,
    PLAYING,
    /** Picture was playing and stalled: a small spinner over the frozen frame. */
    REBUFFERING,
    /** Failed; the engine keeps retrying. Stays until the picture plays, so the card does not flicker. */
    ERROR,
}

/** ExoPlayer's playback states, mirrored so the tracker stays Android-free. */
enum class PlayerPhase { IDLE, BUFFERING, READY, ENDED }

/** Pure state machine behind [PlaybackEngine]'s state; unit tested on the JVM. */
class TuneTracker {
    var state: PlaybackState = PlaybackState.IDLE
        private set

    private var awaitingFirstPicture = false
    private var failed = false

    fun onTune(): PlaybackState {
        awaitingFirstPicture = true
        failed = false
        return set(PlaybackState.TUNING)
    }

    fun onStop(): PlaybackState {
        awaitingFirstPicture = false
        failed = false
        return set(PlaybackState.IDLE)
    }

    fun onPhase(phase: PlayerPhase): PlaybackState = when (phase) {
        PlayerPhase.READY -> {
            awaitingFirstPicture = false
            failed = false
            set(PlaybackState.PLAYING)
        }
        PlayerPhase.BUFFERING -> set(
            when {
                failed -> PlaybackState.ERROR
                awaitingFirstPicture -> PlaybackState.TUNING
                else -> PlaybackState.REBUFFERING
            }
        )
        PlayerPhase.IDLE, PlayerPhase.ENDED -> set(
            when {
                failed -> PlaybackState.ERROR
                awaitingFirstPicture -> PlaybackState.TUNING
                else -> PlaybackState.IDLE
            }
        )
    }

    fun onError(): PlaybackState {
        failed = true
        return set(PlaybackState.ERROR)
    }

    private fun set(s: PlaybackState): PlaybackState { state = s; return s }
}

/** Retry delays for a failing stream: quick at first, then backing off, never giving up. */
class RetryBackoff(private val initialMs: Long = 3_000, private val maxMs: Long = 30_000) {
    private var next = initialMs
    fun nextDelayMs(): Long = next.also { next = (next * 2).coerceAtMost(maxMs) }
    fun reset() { next = initialMs }
}
