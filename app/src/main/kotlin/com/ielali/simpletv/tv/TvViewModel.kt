package com.ielali.simpletv.tv

import android.app.Application
import android.view.KeyEvent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.util.UnstableApi
import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.config.NetworkAddress
import com.ielali.simpletv.data.AppSettings
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.data.YouTubePlayback
import com.ielali.simpletv.player.PlaybackEngine
import com.ielali.simpletv.player.PlaybackState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
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
    val settings: AppSettings = AppSettings(),
    /** True when the YouTube app could not be launched for the current channel; UI falls back to the embed. */
    val externalPlayerUnavailable: Boolean = false,
)

@UnstableApi
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val simpleTv = app as SimpleTvApp
    private val repo = simpleTv.channels
    private val settingsRepo = simpleTv.settings
    private val prefs = app.getSharedPreferences("tv", Application.MODE_PRIVATE)

    val engine = PlaybackEngine(app)
    val tuner = ChannelTuner(viewModelScope)

    private val current = MutableStateFlow<Channel?>(null)
    private val bannerVisible = MutableStateFlow(false)
    private val settingsVisible = MutableStateFlow(false)
    private val externalUnavailable = MutableStateFlow(false)
    private var bannerJob: Job? = null

    /** Channels the Activity should open in the YouTube app (settings say YOUTUBE_APP). */
    private val _openExternal = MutableSharedFlow<Channel>(extraBufferCapacity = 1)
    val openExternal: SharedFlow<Channel> = _openExternal.asSharedFlow()

    val uiState: StateFlow<TvUiState> = combine(
        repo.channels, current, tuner.pendingDigits, bannerVisible, settingsVisible, engine.state,
        settingsRepo.settings, externalUnavailable,
    ) { values ->
        @Suppress("UNCHECKED_CAST")
        TvUiState(
            channels = (values[0] as com.ielali.simpletv.data.ChannelList).sorted(),
            current = values[1] as Channel?,
            pendingDigits = values[2] as String,
            bannerVisible = values[3] as Boolean || (values[2] as String).isNotEmpty(),
            settingsVisible = values[4] as Boolean,
            playback = values[5] as PlaybackState,
            configUrl = NetworkAddress.configUrl(app, SimpleTvApp.CONFIG_PORT),
            settings = values[6] as AppSettings,
            externalPlayerUnavailable = values[7] as Boolean,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvUiState())

    init {
        viewModelScope.launch { tuner.tuneRequests.collect { tuneToNumber(it) } }
        viewModelScope.launch {
            repo.channels.collect { list ->
                val sorted = list.sorted()
                val cur = current.value
                when {
                    sorted.isEmpty() -> switchTo(null)
                    cur == null -> switchTo(ChannelNavigator.resolve(prefs.getInt(KEY_LAST, sorted.first().number), sorted))
                    sorted.none { it.id == cur.id } -> switchTo(ChannelNavigator.resolve(cur.number, sorted))
                    else -> sorted.first { it.id == cur.id }.takeIf { it != cur }?.let { switchTo(it) }
                }
            }
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
            in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> { tuner.onDigit(keyCode - KeyEvent.KEYCODE_0); true }
            in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> { tuner.onDigit(keyCode - KeyEvent.KEYCODE_NUMPAD_0); true }
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP -> { channelUp(); true }
            KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN -> { channelDown(); true }
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                if (!tuner.onConfirm()) showBanner()
                true
            }
            KeyEvent.KEYCODE_INFO, KeyEvent.KEYCODE_GUIDE -> { showBanner(); true }
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { settingsVisible.value = true; true }
            KeyEvent.KEYCODE_BACK -> { tuner.onCancel(); true } // never leave the TV by accident
            else -> false // volume, power, etc. go to the system
        }
    }

    fun channelUp() = switchTo(ChannelNavigator.next(current.value, uiState.value.channels))
    fun channelDown() = switchTo(ChannelNavigator.previous(current.value, uiState.value.channels))

    private fun tuneToNumber(number: Int) {
        switchTo(ChannelNavigator.resolve(number, uiState.value.channels))
    }

    private fun switchTo(channel: Channel?) {
        current.value = channel
        if (channel == null) {
            engine.stop()
            return
        }
        prefs.edit().putInt(KEY_LAST, channel.number).apply()
        externalUnavailable.value = false
        when (channel.type) {
            SourceType.STREAM -> engine.play(channel)
            SourceType.YOUTUBE -> {
                engine.stop()
                if (settingsRepo.current().youtubePlayback == YouTubePlayback.YOUTUBE_APP) {
                    _openExternal.tryEmit(channel) // Activity launches the YouTube app
                }
                // otherwise the WebView in the UI layer takes over
            }
        }
        showBanner()
    }

    /** Called by the Activity when no YouTube app could handle the channel; the embed is used instead. */
    fun onExternalPlayerUnavailable() {
        externalUnavailable.value = true
    }

    private fun showBanner() {
        bannerJob?.cancel()
        bannerVisible.value = true
        bannerJob = viewModelScope.launch {
            delay(BANNER_MS)
            bannerVisible.value = false
        }
    }

    override fun onCleared() {
        engine.release()
    }

    private companion object {
        const val KEY_LAST = "last_channel_number"
        const val BANNER_MS = 3500L
    }
}
