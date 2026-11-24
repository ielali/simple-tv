package com.ielali.simpletv.player

/**
 * Times what the viewer waits for: how long a channel takes to show its first picture, and how often
 * and how long the picture freezes afterwards. Fed with the states [TuneTracker] produces; pure, so
 * it is unit tested on the JVM. [PlaybackEngine] writes the results to the log (ADR-028).
 */
class PlaybackStats(private val clock: () -> Long) {

    /** Something worth a log line as it happens. */
    sealed interface Event {
        fun line(): String

        data class FirstPicture(val afterMs: Long) : Event {
            override fun line() = "first picture after ${seconds(afterMs)}"
        }

        /** The picture is back after freezing; [playedMs] is how long it had played before it froze. */
        data class StallEnded(val lastedMs: Long, val playedMs: Long) : Event {
            override fun line() = "picture froze for ${seconds(lastedMs)} after playing ${seconds(playedMs)}"
        }
    }

    /** One channel's stay, reported when the viewer leaves it. */
    data class Summary(
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
            val first = firstPictureMs?.let { "first picture after ${seconds(it)}" } ?: "no picture"
            return "$first, played ${seconds(playedMs)}, stalls $stalls, frozen ${seconds(stalledMs)} " +
                "(longest ${seconds(longestStallMs)}), errors $errors"
        }
    }

    private var tuned = false
    private var tunedAt = 0L
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

    /** A channel was chosen. Returns the summary of the one that was on, if any. */
    fun onTune(): Summary? {
        val before = finish()
        tuned = true
        tunedAt = clock()
        last = PlaybackState.TUNING
        return before
    }

    /** Playback was stopped. Returns the summary of the channel that was on, if any. */
    fun onStop(): Summary? = finish()

    /** The state the viewer sees changed (or was repeated; repeats are ignored). */
    fun onState(state: PlaybackState): Event? {
        if (!tuned || state == last) return null
        val now = clock()
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
                stalls++
                stalledMs += lasted
                if (lasted > longestStallMs) longestStallMs = lasted
                return Event.StallEnded(lasted, playedSinceStall).also { playedSinceStall = 0L }
            }
            else -> Unit
        }
        return null
    }

    private fun finish(): Summary? {
        if (!tuned) return null
        val now = clock()
        playingSince?.let { playedMs += now - it }
        // Left while frozen: the freeze counts up to the moment the viewer gave up on it.
        frozenSince?.let {
            val lasted = now - it
            stalls++
            stalledMs += lasted
            if (lasted > longestStallMs) longestStallMs = lasted
        }
        val summary = Summary(firstPictureMs, playedMs, stalls, stalledMs, longestStallMs, errors)
        tuned = false
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
