package com.ielali.simpletv.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.ielali.simpletv.data.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Owns the single ExoPlayer instance for STREAM channels.
 *
 * Tuned for live TV: short initial buffer so channel changes feel snappy, keeps retrying a failed
 * stream like a TV would, and no user-visible controls (the remote IS the control). [state] drives
 * the tuning card, the stall spinner and "No signal".
 */
@UnstableApi
class PlaybackEngine(context: Context) {

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val tracker = TuneTracker()
    private val backoff = RetryBackoff()
    private val retryHandler = Handler(Looper.getMainLooper())
    private var currentChannel: Channel? = null

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent(DEFAULT_USER_AGENT)
        .setAllowCrossProtocolRedirects(true)
        .setConnectTimeoutMs(8_000)
        .setReadTimeoutMs(8_000)

    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setMediaSourceFactory(
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context, httpFactory))
                .setLiveTargetOffsetMs(6_000)
        )
        .setLoadControl(
            DefaultLoadControl.Builder()
                .setBufferDurationsMs(3_000, 30_000, 1_000, 2_000)
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
                    _state.value = tracker.onPhase(phase)
                }

                override fun onPlayerError(error: PlaybackException) {
                    _lastError.value = error.errorCodeName
                    _state.value = tracker.onError()
                    // Falling behind the live window just needs a jump back to live; anything else
                    // (network, server, bad stream) is retried with a growing delay, like a TV.
                    scheduleRetry(immediate = error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)
                }
            })
        }

    private fun scheduleRetry(immediate: Boolean) {
        val channel = currentChannel ?: return
        val delayMs = if (immediate) 0L else backoff.nextDelayMs()
        retryHandler.postDelayed({
            if (currentChannel === channel) {
                player.seekToDefaultPosition()
                player.prepare()
            }
        }, delayMs)
    }

    fun play(channel: Channel) {
        retryHandler.removeCallbacksAndMessages(null)
        backoff.reset()
        currentChannel = channel
        _state.value = tracker.onTune()
        _lastError.value = null
        // The data source sets User-Agent itself (overriding request properties), so apply it here:
        // imported channels carry the User-Agent their provider accepted.
        httpFactory.setUserAgent(channel.headers["User-Agent"] ?: DEFAULT_USER_AGENT)
        httpFactory.setDefaultRequestProperties(channel.headers - "User-Agent")
        val item = MediaItem.Builder()
            .setUri(channel.url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).build())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun stop() {
        retryHandler.removeCallbacksAndMessages(null)
        currentChannel = null
        player.stop()
        player.clearMediaItems()
        _state.value = tracker.onStop()
    }

    fun release() {
        retryHandler.removeCallbacksAndMessages(null)
        currentChannel = null
        player.release()
    }

    private companion object {
        const val DEFAULT_USER_AGENT = "SimpleTV/0.1 (Android TV)"
    }
}
