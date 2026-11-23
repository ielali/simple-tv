package com.ielali.simpletv.tv

import android.app.Application
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.config.NetworkAddress
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.ChannelList
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.player.PlaybackEngine
import com.ielali.simpletv.player.PlaybackState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class TvUiState(
    val channels: List<Channel> = emptyList(),
    val current: Channel? = null,
    val pendingDigits: String = "",
    val bannerVisible: Boolean = false,
    val settingsVisible: Boolean = false,
    val playback: PlaybackState = PlaybackState.IDLE,
    val configUrl: String = "",
    /** The current YouTube channel plays in the YouTube app; this screen only shows a holding card. */
    val youtubeInApp: Boolean = false,
)

/** Screen adapter: video surface and the settings overlay. Channel state lives in [TvController]. */
@UnstableApi
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val simpleTv = app as SimpleTvApp
    val controller: TvController = simpleTv.tv
    val engine = PlaybackEngine(app)

    private val settingsVisible = MutableStateFlow(false)

    val uiState: StateFlow<TvUiState> = combine(
        simpleTv.channels.channels, controller.current, controller.tuner.pendingDigits, controller.bannerVisible,
        settingsVisible, engine.state, simpleTv.settings.settings, controller.externalUnavailable,
    ) { values ->
        val current = values[1] as Channel?
        TvUiState(
            channels = (values[0] as ChannelList).sorted(),
            current = current,
            pendingDigits = values[2] as String,
            bannerVisible = values[3] as Boolean,
            settingsVisible = values[4] as Boolean,
            playback = values[5] as PlaybackState,
            configUrl = NetworkAddress.configUrl(app, SimpleTvApp.CONFIG_PORT),
            youtubeInApp = controller.usesExternalPlayer(current),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvUiState())

    init {
        // Streams play on this screen; YouTube channels use the WebView or the YouTube app.
        viewModelScope.launch {
            controller.current.collect { ch -> if (ch?.type == SourceType.STREAM) engine.play(ch) else engine.stop() }
        }
    }

    /** Keys the TV owns. Everything else (volume, power, mute) must reach the system untouched. */
    fun handles(keyCode: Int): Boolean = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9,
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9,
        KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
        KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE,
        KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS,
        KeyEvent.KEYCODE_BACK -> true
        else -> false
    }

    /** Central remote handler for the first DOWN of a handled key. Returns true when consumed. */
    fun onKeyDown(keyCode: Int): Boolean {
        if (settingsVisible.value) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) settingsVisible.value = false
            return true
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { settingsVisible.value = true; true }
            else -> controller.onRemoteKey(keyCode)
        }
    }

    override fun onCleared() {
        engine.release()
    }
}
