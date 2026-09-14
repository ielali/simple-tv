package com.ielali.simpletv.player

import android.content.Context
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

enum class PlaybackState { IDLE, BUFFERING, PLAYING, ERROR }

/**
 * Owns the single ExoPlayer instance for STREAM channels.
 *
 * Tuned for live TV: short initial buffer so channel changes feel snappy, automatic retry on
 * transient network errors, and no user-visible controls (the remote IS the control).
 */
@UnstableApi
class PlaybackEngine(context: Context) {

    private val _state = MutableStateFlow(PlaybackState.IDLE)
    val state: StateFlow<PlaybackState> = _state

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    private val httpFactory = DefaultHttpDataSource.Factory()
        .setUserAgent("SimpleTV/0.1 (Android TV)")
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
                    _state.value = when (playbackState) {
                        Player.STATE_BUFFERING -> PlaybackState.BUFFERING
                        Player.STATE_READY -> PlaybackState.PLAYING
                        Player.STATE_ENDED -> PlaybackState.IDLE
                        else -> PlaybackState.IDLE
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    _state.value = PlaybackState.ERROR
                    _lastError.value = error.errorCodeName
                    // Live streams drop out; behave like a TV and keep trying rather than showing a dialog.
                    if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW ||
                        error.errorCode in PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED..PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
                    ) {
                        seekToDefaultPosition()
                        prepare()
                    }
                }
            })
        }

    fun play(channel: Channel) {
        _lastError.value = null
        httpFactory.setDefaultRequestProperties(channel.headers)
        val item = MediaItem.Builder()
            .setUri(channel.url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(6_000).build())
            .build()
        player.setMediaItem(item)
        player.prepare()
        player.play()
    }

    fun stop() {
        player.stop()
        player.clearMediaItems()
        _state.value = PlaybackState.IDLE
    }

    fun release() = player.release()
}
