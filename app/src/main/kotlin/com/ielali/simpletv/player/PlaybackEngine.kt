package com.ielali.simpletv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ielali.simpletv.data.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Owns the single ExoPlayer instance for STREAM channels.
 *
 * Tuned for live TV: the picture starts after a second of buffered video so channel changes feel
 * snappy, then the player keeps as much in hand as the stream allows so a slow moment on the line does
 * not freeze the picture (ADR-028). Keeps retrying a failed stream like a TV would, and has no
 * user-visible controls (the remote IS the control). [state] drives the tuning card, the stall
 * spinner and "No signal".
 */
@UnstableApi
class PlaybackEngine(context: Context) {

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    /** Fires when a film or episode reaches its end, so the queue can move on. */
    private val _ended = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val ended: SharedFlow<Unit> = _ended.asSharedFlow()

    /** Subtitles and soundtrack the file offered and what was chosen (films and episodes only). */
    private val _tracks = MutableStateFlow<TrackPicker.Report?>(null)
    val tracks: StateFlow<TrackPicker.Report?> = _tracks

    private var wish: TrackPicker.Wish? = null
    private var appliedGroups = -1

    private val tracker = TuneTracker()
    private val stats = PlaybackStats(SystemClock::elapsedRealtime)
    private val backoff = RetryBackoff()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var currentChannel: Channel? = null
    private var currentLive = true

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(DEFAULT_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(8_000)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        // No live offset of our own: a live stream plays as far behind as it asks for, or three
        // segments behind when it does not say (HLS's rule). Closer than that there is nothing left to
        // buffer, and every slow segment freezes the picture (ADR-028).
        .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory)))
        .setLoadControl(
            DefaultLoadControl.Builder()
                // Minimum equal to maximum: loading carries on whenever there is room. A lower minimum
                // makes the player stop at the maximum and wait until that little is left.
                .setBufferDurationsMs(BUFFER_MS, BUFFER_MS, START_AFTER_MS, RESUME_AFTER_MS)
                .build()
        )
        .setWakeMode(C.WAKE_MODE_NETWORK)
        .build()
        .apply {
            playWhenReady = true
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    val phase = when (playbackState) {
                        Player.STATE_BUFFERING -> PlayerPhase.BUFFERING
                        Player.STATE_READY -> PlayerPhase.READY
                        Player.STATE_ENDED -> PlayerPhase.ENDED
                        else -> PlayerPhase.IDLE
                    }
                    if (phase == PlayerPhase.READY) backoff.reset()
                    show(tracker.onPhase(phase))
                    if (phase == PlayerPhase.ENDED && currentChannel != null) _ended.tryEmit(Unit)
                }

                override fun onTracksChanged(tracks: Tracks) = applyWish(tracks)

                override fun onPlayerError(error: PlaybackException) {
                    _lastError.value = error.errorCodeName
                    Log.i(TAG, "${label()}: ${error.errorCodeName}")
                    show(tracker.onError())
                    // Falling behind the live window just needs a jump back to live; anything else
                    // (network, server, bad stream) is retried with a growing delay, like a TV.
                    scheduleRetry(immediate = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
                }
            })
        }

    /** Publishes what the viewer sees, and logs how long the picture took or froze (no addresses). */
    private fun show(state: PlaybackState) {
        _state.value = state
        stats.onState(state)?.let { Log.i(TAG, "${label()}: ${it.line()}") }
    }

    private fun label(): String = "ch ${currentChannel?.number ?: "-"}"

    private fun logSummary(summary: PlaybackStats.Summary?) {
        if (summary != null) Log.i(TAG, "${label()}: left; ${summary.line()}")
    }

    private fun scheduleRetry(immediate: Boolean) {
        val channel = currentChannel ?: return
        val delayMs = if (immediate) 0L else backoff.nextDelayMs()
        retryHandler.postDelayed({
            if (currentChannel === channel) {
                // Live channels jump back to live; a film or episode carries on where it stopped.
                if (currentLive) player.seekToDefaultPosition()
                player.prepare()
            }
        }, delayMs)
    }

    fun play(channel: Channel) = play(channel, channel.url, channel.headers, startPositionMs = 0L, live = true)

    /**
     * Films and episodes: once the file's tracks are known, show the wished subtitles and soundtrack
     * (TrackPicker). Applied again only if more tracks appear later, never fighting the player.
     */
    private fun applyWish(tracks: Tracks) {
        val w = wish ?: return
        if (tracks.groups.isEmpty() || tracks.groups.size == appliedGroups) return
        val text = ArrayList<TrackPicker.Track>()
        val audio = ArrayList<TrackPicker.Track>()
        tracks.groups.forEachIndexed { gi, g ->
            for (i in 0 until g.length) {
                if (!g.isTrackSupported(i)) continue
                val f = g.getTrackFormat(i)
                val t = TrackPicker.Track(
                    gi, i, f.language,
                    isDefault = f.selectionFlags and C.SELECTION_FLAG_DEFAULT != 0,
                    isForced = f.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                )
                when (g.type) {
                    C.TRACK_TYPE_TEXT -> text += t
                    C.TRACK_TYPE_AUDIO -> audio += t
                }
            }
        }
        appliedGroups = tracks.groups.size
        val params = player.trackSelectionParameters.buildUpon()
        when (val c = TrackPicker.subtitles(text, w.subtitles)) {
            TrackPicker.Choice.Off -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            is TrackPicker.Choice.Pick -> params.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(tracks.groups[c.group].mediaTrackGroup, c.index))
            TrackPicker.Choice.Keep -> Unit
        }
        val a = TrackPicker.audio(audio, w.audio)
        if (a is TrackPicker.Choice.Pick) {
            params.setOverrideForType(TrackSelectionOverride(tracks.groups[a.group].mediaTrackGroup, a.index))
        }
        player.trackSelectionParameters = params.build()
        _tracks.value = TrackPicker.report(text, audio, w)
    }

    /**
     * Plays [url] for [channel] (a film or episode of a film/series channel uses its own address),
     * from [startPositionMs] for films and episodes that were part-watched.
     */
    fun play(
        channel: Channel, url: String, headers: Map<String, String>, startPositionMs: Long, live: Boolean,
        tracks: TrackPicker.Wish? = null,
    ) {
        retryHandler.removeCallbacksAndMessages(null)
        // Each item starts from the player's own track choice; a film's wishes apply once its tracks load.
        wish = tracks
        appliedGroups = -1
        _tracks.value = null
        player.trackSelectionParameters = player.trackSelectionParameters.buildUpon()
            .clearOverrides()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        backoff.reset()
        logSummary(stats.onTune())
        currentChannel = channel
        currentLive = live
        show(tracker.onTune())
        _lastError.value = null
        // The data source sets User-Agent itself (overriding request properties), so apply it here:
        // imported channels carry the User-Agent their provider accepted.
        httpFactory.setUserAgent(headers["User-Agent"] ?: DEFAULT_USER_AGENT)
        httpFactory.setDefaultRequestProperties(headers - "User-Agent")
        // A live channel starts where the stream says live playback should start, not at a position of ours.
        player.setMediaItem(MediaItem.fromUri(url), if (live) C.TIME_UNSET else startPositionMs.coerceAtLeast(0L))
        player.prepare()
        player.play()
    }

    fun seekTo(positionMs: Long) = player.seekTo(positionMs.coerceAtLeast(0L))

    /** Current position, for remembering where a film or episode stopped. */
    fun positionMs(): Long = player.currentPosition

    fun stop() {
        retryHandler.removeCallbacksAndMessages(null)
        wish = null
        _tracks.value = null
        logSummary(stats.onStop())
        currentChannel = null
        player.stop()
        player.clearMediaItems()
        show(tracker.onStop())
    }

    fun release() {
        retryHandler.removeCallbacksAndMessages(null)
        logSummary(stats.onStop())
        currentChannel = null
        player.release()
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "SimpleTV/0.1 (Android TV)"

        /** `adb logcat -s SimpleTvPlayback` shows first-picture times and freezes per channel. */
        const val TAG = "SimpleTvPlayback"

        /** How much video the player keeps in hand; a live stream offers less (its distance from live). */
        const val BUFFER_MS = 30_000

        /** The picture starts once this much is buffered, and comes back after a stall with this much. */
        const val START_AFTER_MS = 1_000
        const val RESUME_AFTER_MS = 2_000
    }
}
