package com.ielali.simpletv.player

/**
 * Times what the viewer waits for: how long a channel takes to show its first picture, and how often
 * and how long the picture freezes afterwards. Fed with the states [TuneTracker] produces; pure, so
 * it is unit tested on the JVM. [PlaybackEngine] writes the results to the log (ADR-028).
 *
 * Only time someone can be watching counts: while playback is paused (the viewer paused a film, or
 * the screen is off) this class's clock stands still, so standby adds neither played nor frozen time.
 */
class PlaybackStats(
    private val clock: () -> Long,
    /** A freeze shorter than this is not counted; the viewer does not see it. */
    private val minStallMs: Long = 250L,
) {

    /** Something worth a log line as it happens. */
    sealed interface Event {
        fun line(): String

        /** The first video frame was drawn. The tuning card can still be covering it. */
        data class FirstFrame(val afterMs: Long) : Event {
            override fun line() = "first frame drawn after ${seconds(afterMs)}"
        }

        /**
         * The tuning card went away: what the viewer sees as the picture arriving. With a video track
         * on screen the player is not ready before its first frame is drawn, so this is never earlier
         * than [FirstFrame]; it is later by the time the start buffer takes to fill.
         */
        data class FirstPicture(val afterMs: Long) : Event {
            override fun line() = "first picture after ${seconds(afterMs)}"
        }

        /** The picture is back after freezing; [playedMs] is how long it had played before it froze. */
        data class StallEnded(val lastedMs: Long, val playedMs: Long) : Event {
            override fun line() = "picture froze for ${seconds(lastedMs)} after playing ${seconds(playedMs)}"
        }
    }

    /** One channel's stay, reported when the viewer leaves it. Times leave out paused time. */
    data class Summary(
        /** Null when no video frame was drawn (no picture, or a channel with sound only). */
        val firstFrameMs: Long?,
        /** Null when the picture never came. */
        val firstPictureMs: Long?,
        /** Time the picture was moving. */
        val playedMs: Long,
        val stalls: Int,
        val stalledMs: Long,
        val longestStallMs: Long,
        val errors: Int,
    ) {
        fun line(): String {
            val frame = firstFrameMs?.let { "first frame after ${seconds(it)}" } ?: "no frame"
            val picture = firstPictureMs?.let { "first picture after ${seconds(it)}" } ?: "no picture"
            return "$frame, $picture, played ${seconds(playedMs)}, stalls $stalls, frozen ${seconds(stalledMs)} " +
                "(longest ${seconds(longestStallMs)}), errors $errors"
        }
    }

    private var pausedAt: Long? = null
    private var pausedTotal = 0L

    private var tuned = false
    private var tunedAt = 0L
    private var firstFrameMs: Long? = null
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
        return Event.FirstFrame(after)
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
            // After the first picture, a stall or a failure is a freeze until the picture is back.
            PlaybackState.REBUFFERING, PlaybackState.ERROR ->
                if (firstPictureMs != null && frozenSince == null) frozenSince = now
            PlaybackState.PLAYING -> {
                playingSince = now
                if (firstPictureMs == null) {
                    firstPictureMs = now - tunedAt
                    return Event.FirstPicture(now - tunedAt)
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
        val summary = Summary(firstFrameMs, firstPictureMs, playedMs, stalls, stalledMs, longestStallMs, errors)
        tuned = false
        firstFrameMs = null
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
