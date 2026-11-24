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
import com.ielali.simpletv.data.NowNext
import com.ielali.simpletv.data.QueuePlayback
import com.ielali.simpletv.data.SecretsStore
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.data.playsOnSimpleTv
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import com.ielali.simpletv.player.PlaybackEngine
import com.ielali.simpletv.player.PlaybackState
import com.ielali.simpletv.player.QueueKeys
import com.ielali.simpletv.player.TrackPicker
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
    /** Film or episode now playing on a film/series channel. */
    val nowTitle: String? = null,
    /** The config page PIN, shown only on the TV's settings screen. */
    val pin: String? = null,
    /** A film or episode paused with OK. */
    val paused: Boolean = false,
    /** Live channels: what is on now and next, from the TV guide. */
    val guide: NowNext? = null,
    /** The channel list (ADR-022): the highlighted row while it is open, and that channel's guide. */
    val listIndex: Int? = null,
    val listGuide: NowNext? = null,
)

/** Screen adapter: video surface and the settings overlay. Channel state lives in [TvController]. */
@UnstableApi
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val simpleTv = app as SimpleTvApp
    val controller: TvController = simpleTv.tv
    val engine = PlaybackEngine(app)

    private val settingsVisible = MutableStateFlow(false)
    private val paused = MutableStateFlow(false)

    val uiState: StateFlow<TvUiState> = combine(
        simpleTv.channels.channels, controller.current, controller.tuner.pendingDigits, controller.bannerVisible,
        settingsVisible, engine.state, simpleTv.settings.settings, controller.externalUnavailable,
        simpleTv.queue.nowTitle, simpleTv.secrets.secrets, paused, simpleTv.guide.version, controller.listIndex,
    ) { values ->
        val current = values[1] as Channel?
        @Suppress("UNCHECKED_CAST")
        val now = values[8] as Pair<String, String>?
        val sorted = (values[0] as ChannelList).sorted()
        val listIndex = (values[12] as Int?)?.takeIf { it in sorted.indices }
        TvUiState(
            channels = sorted,
            current = current,
            pendingDigits = values[2] as String,
            bannerVisible = values[3] as Boolean,
            settingsVisible = values[4] as Boolean,
            playback = values[5] as PlaybackState,
            configUrl = NetworkAddress.configUrl(app, SimpleTvApp.CONFIG_PORT),
            youtubeInApp = controller.usesExternalPlayer(current),
            nowTitle = now?.takeIf { it.first == current?.id && current.playlist != null }?.second,
            pin = (values[9] as SecretsStore.Secrets).pin,
            paused = values[10] as Boolean,
            guide = current?.let { simpleTv.guide.nowNext(it) },
            listIndex = listIndex,
            listGuide = listIndex?.let { simpleTv.guide.nowNext(sorted[it]) },
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvUiState())

    /** The film/series channel and item playing now, so progress is saved against the right one. */
    private var queueItem: Pair<Channel, String>? = null

    init {
        // Streams and film/series channels play on this screen; YouTube uses the WebView or the YouTube app.
        viewModelScope.launch {
            controller.current.collectLatest { ch ->
                saveQueueProgress()
                ch?.let { c -> viewModelScope.launch { runCatching { simpleTv.guide.touch(c) } } }
                queueItem = null
                paused.value = false
                simpleTv.queue.report(null)
                when {
                    ch == null -> engine.stop()
                    ch.type == SourceType.STREAM -> engine.play(ch)
                    ch.playlist != null && ch.playsOnSimpleTv -> playQueued(ch, simpleTv.queue.current(ch))
                    else -> engine.stop()
                }
            }
        }
        // The channel list: it replaces the settings card, and asks the guide about the highlighted channel
        // once the viewer stops on it.
        viewModelScope.launch {
            controller.listIndex.collectLatest { i ->
                if (i == null) return@collectLatest
                settingsVisible.value = false
                delay(400)
                controller.listChannel()?.let { c -> runCatching { simpleTv.guide.touch(c) } }
            }
        }
        // Tell the config page what subtitles and soundtracks the film on screen has.
        viewModelScope.launch {
            engine.tracks.collect { r ->
                val id = controller.current.value?.id
                simpleTv.nowTracks.value = if (r != null && id != null) id to r else null
            }
        }
        // Moves asked for on the config page (next, back to the start, pause, a chosen item).
        viewModelScope.launch {
            simpleTv.queue.commands.collect { c ->
                val ch = controller.current.value ?: return@collect
                if (ch.id != c.channelId || ch.playlist == null) return@collect
                if (c.reload) playQueued(ch, simpleTv.queue.current(ch), banner = true)
                else c.action?.let { queueAction(ch, it) }
            }
        }
        // A film or episode finished: move to the next one in the queue.
        viewModelScope.launch {
            engine.ended.collect {
                val ch = controller.current.value ?: return@collect
                if (ch.playlist != null) playQueued(ch, simpleTv.queue.advance(ch))
            }
        }
        // Remember where a film or episode stopped, so the channel resumes there.
        viewModelScope.launch {
            while (true) {
                delay(15_000)
                if (engine.state.value == PlaybackState.PLAYING) saveQueueProgress()
            }
        }
        // Flag channels that keep failing for the lineup page; clear the flag once they play.
        viewModelScope.launch {
            engine.state.collectLatest { st ->
                val id = controller.current.value?.id ?: return@collectLatest
                when (st) {
                    PlaybackState.PLAYING -> simpleTv.catalog.reportPlayed(id)
                    PlaybackState.ERROR -> {
                        delay(20_000)
                        if (controller.current.value?.id == id) simpleTv.catalog.reportFailed(id)
                    }
                    else -> Unit
                }
            }
        }
    }

    private fun playQueued(ch: Channel, item: QueuePlayback.Playable?, banner: Boolean = false) {
        setPaused(false)
        if (item == null) {
            engine.stop()
            simpleTv.queue.report(null)
            return
        }
        queueItem = ch to item.itemId
        simpleTv.queue.report(QueuePlayback.State(ch.id, item.itemId, paused = false))
        if (banner) controller.showBanner()
        val spec = ch.playlist
        engine.play(ch, item.url, item.headers, item.startPositionMs, live = false,
            tracks = spec?.let { TrackPicker.Wish(it.subtitles, it.audio) })
    }

    private suspend fun saveQueueProgress() {
        val (ch, itemId) = queueItem ?: return
        val pos = engine.positionMs()
        if (pos > 0) simpleTv.queue.save(ch.id, itemId, pos)
    }

    /** A film or series channel playing on this screen (where Left, Right and OK move within it). */
    private fun queueChannel(): Channel? = controller.current.value?.takeIf { it.playlist != null && it.playsOnSimpleTv }

    private fun setPaused(p: Boolean) {
        paused.value = p
        queueItem?.let { (ch, item) -> simpleTv.queue.report(QueuePlayback.State(ch.id, item, p)) }
    }

    /** Right, Left, OK (and media keys) on a film or series channel. */
    private fun queueAction(ch: Channel, a: QueueKeys.Action) {
        when (a) {
            QueueKeys.Action.NEXT -> viewModelScope.launch { playQueued(ch, simpleTv.queue.advance(ch), banner = true) }
            QueueKeys.Action.PREVIOUS -> viewModelScope.launch { playQueued(ch, simpleTv.queue.back(ch), banner = true) }
            QueueKeys.Action.RESTART -> { engine.seekTo(0); setPaused(false); engine.player.play(); controller.showBanner() }
            QueueKeys.Action.TOGGLE_PAUSE -> if (paused.value) queueAction(ch, QueueKeys.Action.PLAY) else queueAction(ch, QueueKeys.Action.PAUSE)
            QueueKeys.Action.PAUSE -> { engine.player.pause(); setPaused(true); viewModelScope.launch { saveQueueProgress() } }
            QueueKeys.Action.PLAY -> { setPaused(false); engine.player.play() }
            QueueKeys.Action.FORWARD -> engine.seekTo(engine.positionMs() + QueueKeys.JUMP_MS)
            QueueKeys.Action.BACK -> engine.seekTo(engine.positionMs() - QueueKeys.JUMP_MS)
        }
    }

    /** The screen came back (standby, another app): carry on unless the viewer paused. */
    fun onScreenResumed() { engine.player.playWhenReady = !paused.value }

    /** Keys the TV owns. Everything else (volume, power, mute) must reach the system untouched. */
    fun handles(keyCode: Int): Boolean = controller.roleOf(keyCode) != null ||
        (controller.isListOpen() && ChannelListNav.onKey(keyCode, 0, 1, 1) != ChannelListNav.Step.Pass) ||
        (keyCode in QueueKeys.KEYS && queueChannel() != null) || when (keyCode) {
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
        // A button the caregiver gave a job (the Live TV button opens the channel list).
        when (controller.roleOf(keyCode)) {
            com.ielali.simpletv.guard.ButtonRoles.Role.CHANNEL_LIST -> { settingsVisible.value = false; controller.toggleList(); return true }
            com.ielali.simpletv.guard.ButtonRoles.Role.PREVIOUS_CHANNEL -> { settingsVisible.value = false; controller.previousChannel(); return true }
            null -> Unit
        }
        if (controller.isListOpen()) {
            if (keyCode == KeyEvent.KEYCODE_MENU || keyCode == KeyEvent.KEYCODE_SETTINGS) {
                controller.closeList(); settingsVisible.value = true; return true
            }
            return controller.onListKey(keyCode)
        }
        if (settingsVisible.value) {
            if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_MENU) settingsVisible.value = false
            return true
        }
        val q = queueChannel()
        if (q != null && controller.tuner.pendingDigits.value.isEmpty()) {
            QueueKeys.action(keyCode, engine.positionMs())?.let { queueAction(q, it); return true }
        }
        return when (keyCode) {
            KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_SETTINGS -> { settingsVisible.value = true; true }
            else -> controller.onRemoteKey(keyCode)
        }
    }

    /** A held arrow or channel key keeps moving through the list. */
    fun onKeyRepeat(keyCode: Int) {
        if (controller.isListOpen() && keyCode in LIST_REPEAT_KEYS) controller.onListKey(keyCode)
    }

    private companion object {
        val LIST_REPEAT_KEYS = setOf(
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_CHANNEL_UP, KeyEvent.KEYCODE_CHANNEL_DOWN, KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN,
        )
    }

    override fun onCleared() {
        queueItem?.let { (ch, itemId) ->
            val pos = engine.positionMs()
            if (pos > 0) simpleTv.appScope.launch { simpleTv.queue.save(ch.id, itemId, pos) }
        }
        engine.release()
    }
}
