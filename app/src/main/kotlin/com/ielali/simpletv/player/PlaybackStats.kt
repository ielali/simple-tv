package com.ielali.simpletv.player

/**
 * Times what the viewer waits for: how long a channel takes to show its first picture, and how often
 * and how long the picture freezes afterwards. Fed with the states [TuneTracker] produces; pure, so
 * it is unit tested on the JVM. [PlaybackEngine] writes the results to the log (ADR-028).
 *
 * A picture needs two things: the player ready (the tuning card goes away) and a video frame drawn.
 * With the screen on the frame comes first and the picture arrives when the player is ready. With the
 * screen off, the app behind another one, or a channel with sound only, the player gets ready without
 * drawing anything, and that is reported as ready, not as a picture.
 *
 * While playback is paused (the viewer paused a film, or the screen went off) this class's clock
 * stands still, so that time adds neither played nor frozen time.
 */
class PlaybackStats(
    private val clock: () -> Long,
    /** A freeze shorter than this is not counted; the viewer does not see it. */
    private val minStallMs: Long = 250L,
) {

    /** Something worth a log line as it happens. */
    sealed interface Event {
        fun line(): String

        /** The first video frame was drawn; the tuning card still covers it until the player is ready. */
        data class FirstFrame(val afterMs: Long) : Event {
            override fun line() = "first frame drawn after ${seconds(afterMs)}"
        }

        /** The player is ready and nothing has been drawn: screen off, app not in front, or sound only. */
        data class ReadyWithoutPicture(val afterMs: Long) : Event {
            override fun line() = "ready after ${seconds(afterMs)}, no frame drawn (screen off or sound only)"
        }

        /** A frame is drawn and the tuning card has gone: what the viewer sees as the picture arriving. */
        data class FirstPicture(val afterMs: Long) : Event {
            override fun line() = "first picture after ${seconds(afterMs)}"
        }

        /** Playback is moving again after freezing; [playedMs] is how long it had played before it froze. */
        data class StallEnded(val lastedMs: Long, val playedMs: Long) : Event {
            override fun line() = "picture froze for ${seconds(lastedMs)} after playing ${seconds(playedMs)}"
        }
    }

    /** One channel's stay, reported when the viewer leaves it. Times leave out paused time. */
    data class Summary(
        /** Null when no video frame was drawn: the screen was off, or the channel has sound only. */
        val firstFrameMs: Long?,
        /** Null when the player never got ready. */
        val readyMs: Long?,
        /** When the player was ready with a frame drawn; null when that never happened. */
        val firstPictureMs: Long?,
        /** Time playback was moving. */
        val playedMs: Long,
        val stalls: Int,
        val stalledMs: Long,
        val longestStallMs: Long,
        val errors: Int,
    ) {
        fun line(): String {
            val start = when {
                firstPictureMs != null && firstFrameMs != null ->
                    "first frame after ${seconds(firstFrameMs)}, first picture after ${seconds(firstPictureMs)}"
                readyMs != null -> "no picture (ready after ${seconds(readyMs)}, no frame drawn)"
                else -> "no picture (never ready)"
            }
            return "$start, played ${seconds(playedMs)}, stalls $stalls, frozen ${seconds(stalledMs)} " +
                "(longest ${seconds(longestStallMs)}), errors $errors"
        }
    }

    private var pausedAt: Long? = null
    private var pausedTotal = 0L

    private var tuned = false
    private var tunedAt = 0L
    private var firstFrameMs: Long? = null
    private var readyMs: Long? = null
    private var firstPictureMs: Long? = null
    private var playingSince: Long? = null
    private var frozenSince: Long? = null
    private var playedSinceStall = 0L
    private var playedMs = 0L
    private var stalls = 0
    private var stalledMs = 0L
    private var longestStallMs = 0L
    private var errors = 0
    private var last = PlaybackState.IDLE

    /** The clock everything here is timed with: it stands still while playback is paused. */
    private fun now(): Long = (pausedAt ?: clock()) - pausedTotal

    /** Playback was paused or resumed: by the viewer on a film, or because the screen went off or came back. */
    fun onPaused(paused: Boolean) {
        val since = pausedAt
        if (paused) {
            if (since == null) pausedAt = clock()
        } else if (since != null) {
            pausedTotal += clock() - since
            pausedAt = null
        }
    }

    /** A channel was chosen. Returns the summary of the one that was on, if any. */
    fun onTune(): Summary? {
        val before = finish()
        tuned = true
        tunedAt = now()
        last = PlaybackState.TUNING
        return before
    }

    /** Playback was stopped. Returns the summary of the channel that was on, if any. */
    fun onStop(): Summary? = finish()

    /** A video frame was drawn for the first time since the surface or the stream changed. */
    fun onFirstFrame(): Event? {
        if (!tuned || firstFrameMs != null) return null
        val after = now() - tunedAt
        firstFrameMs = after
        // Usually the frame is drawn under the tuning card. Drawn after the player was ready (the
        // screen came on later), it is the picture arriving.
        if (readyMs == null) return Event.FirstFrame(after)
        firstPictureMs = after
        return Event.FirstPicture(after)
    }

    /** The state the viewer sees changed (or was repeated; repeats are ignored). */
    fun onState(state: PlaybackState): Event? {
        if (!tuned || state == last) return null
        val now = now()
        val was = last
        last = state
        if (state == PlaybackState.ERROR) errors++
        if (was == PlaybackState.PLAYING) {
            val ran = now - (playingSince ?: now)
            playedMs += ran
            playedSinceStall += ran
            playingSince = null
        }
        when (state) {
            // Once playback has started, a stall or a failure is a freeze until it is moving again.
            PlaybackState.REBUFFERING, PlaybackState.ERROR ->
                if (readyMs != null && frozenSince == null) frozenSince = now
            PlaybackState.PLAYING -> {
                playingSince = now
                if (readyMs == null) {
                    val after = now - tunedAt
                    readyMs = after
                    if (firstFrameMs == null) return Event.ReadyWithoutPicture(after)
                    firstPictureMs = after
                    return Event.FirstPicture(after)
                }
                val since = frozenSince ?: return null
                frozenSince = null
                val lasted = now - since
                if (!countStall(lasted)) return null
                return Event.StallEnded(lasted, playedSinceStall).also { playedSinceStall = 0L }
            }
            else -> Unit
        }
        return null
    }

    private fun countStall(lasted: Long): Boolean {
        if (lasted < minStallMs) return false
        stalls++
        stalledMs += lasted
        if (lasted > longestStallMs) longestStallMs = lasted
        return true
    }

    private fun finish(): Summary? {
        if (!tuned) return null
        val now = now()
        playingSince?.let { playedMs += now - it }
        // Left while frozen: the freeze counts up to the moment the viewer gave up on it.
        frozenSince?.let { countStall(now - it) }
        val summary = Summary(firstFrameMs, readyMs, firstPictureMs, playedMs, stalls, stalledMs, longestStallMs, errors)
        tuned = false
        firstFrameMs = null
        readyMs = null
        firstPictureMs = null
        playingSince = null
        frozenSince = null
        playedSinceStall = 0L
        playedMs = 0L
        stalls = 0
        stalledMs = 0L
        longestStallMs = 0L
        errors = 0
        last = PlaybackState.IDLE
        return summary
    }

    private companion object {
        /** Always Western digits, whatever the TV language: these lines are read in a log, not on the TV. */
        fun seconds(ms: Long): String = String.format(java.util.Locale.US, "%.1f s", ms / 1000.0)
    }
}
