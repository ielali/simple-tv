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
    /** The list's preview window shows the highlighted channel's picture (the player is playing it). */
    val previewOn: Boolean = false,
    /** A one-line hint under the banner: how the remote works on this channel, or how to undo a skip. */
    val hint: TvHint? = null,
    /** The YouTube playlist on screen has stopped playing (errors one after another). */
    val ytProblem: Boolean = false,
    /** A film or series channel with nothing to play: still checking its films, or none at all. */
    val queueEmpty: QueueEmpty? = null,
    /** Until when the home guard is paused (0 = not paused). */
    val guardPausedUntil: Long = 0L,
    val showClock: Boolean = true,
)

enum class TvHint { CONTROLS, SKIPPED }
enum class QueueEmpty { CHECKING, NOTHING }

/** Screen adapter: video surface and the settings overlay. Channel state lives in [TvController]. */
@UnstableApi
class TvViewModel(app: Application) : AndroidViewModel(app) {

    private val simpleTv = app as SimpleTvApp
    val controller: TvController = simpleTv.tv
    val engine = PlaybackEngine(app)

    private val settingsVisible = MutableStateFlow(false)
    private val paused = MutableStateFlow(false)

    /**
     * The channel the player is playing: the current one, or one previewed in the channel list. There
     * is one player, so a preview replaces the channel (most IPTV accounts allow a single stream).
     */
    private val playing = MutableStateFlow<String?>(null)
    /** A live channel playing in the preview window; OK on it carries on without tuning again. */
    private var previewedId: String? = null

    private val hint = MutableStateFlow<TvHint?>(null)
    private var hintJob: kotlinx.coroutines.Job? = null
    private val ytProblem = MutableStateFlow<String?>(null)
    private val queueEmpty = MutableStateFlow<QueueEmpty?>(null)
    private var emptyRetry: kotlinx.coroutines.Job? = null
    /** Right on a film, series or playlist: what was skipped and where, so Left can bring it back for 5 s. */
    private var skipped: Triple<String, Long, Long>? = null

    /** YouTube playlist channels (ADR-023): the video playing (channel id to title), where, and commands. */
    private val ytNow = MutableStateFlow<Pair<String, String>?>(null)
    @Volatile private var ytPositionMs = 0L
    @Volatile private var ytPauseAsked = false
    private val _ytCommands = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 8)
    val ytCommands: kotlinx.coroutines.flow.SharedFlow<String> = _ytCommands

    val uiState: StateFlow<TvUiState> = combine(
        simpleTv.channels.channels, controller.current, controller.tuner.pendingDigits, controller.bannerVisible,
        settingsVisible, engine.state, simpleTv.settings.settings, controller.externalUnavailable,
        simpleTv.queue.nowTitle, simpleTv.secrets.secrets, paused, simpleTv.guide.version, controller.listIndex,
        playing, ytNow, hint, ytProblem, queueEmpty,
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
            nowTitle = now?.takeIf { it.first == current?.id && current.playlist != null }?.second
                ?: (values[14] as Pair<*, *>?)?.takeIf { it.first == current?.id }?.second as String?,
            pin = (values[9] as SecretsStore.Secrets).pin,
            paused = values[10] as Boolean,
            guide = current?.let { simpleTv.guide.nowNext(it) },
            listIndex = listIndex,
            listGuide = listIndex?.let { simpleTv.guide.nowNext(sorted[it]) },
            previewOn = listIndex != null && sorted[listIndex].playsOnSimpleTv && values[13] == sorted[listIndex].id,
            hint = values[15] as TvHint?,
            ytProblem = values[16] != null && values[16] == current?.id,
            queueEmpty = values[17] as QueueEmpty?,
            guardPausedUntil = (values[6] as com.ielali.simpletv.data.AppSettings).let { if (it.guardEnabled) it.guardPausedUntil else 0L },
            showClock = (values[6] as com.ielali.simpletv.data.AppSettings).showClock,
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
                // OK on a live channel already playing in the list's preview window: no new tuning.
                if (ch != null && ch.type == SourceType.STREAM && ch.id == previewedId && ch.id == playing.value) {
                    previewedId = null
                    return@collectLatest
                }
                previewedId = null
                ytPositionMs = 0L; ytPauseAsked = false
                ytProblem.value = null; skipped = null
                playChannel(ch)
                ch?.let(::maybeTeach)
            }
        }
        // The channel list: it replaces the settings card. Once the viewer rests on a channel for a moment,
        // the preview window plays it and the guide is asked about it. Closed without choosing, the channel
        // that was on comes back.
        viewModelScope.launch {
            var open = false
            var openedOn: String? = null
            controller.listIndex.collectLatest { i ->
                if (i == null) {
                    if (!open) return@collectLatest
                    open = false
                    val cur = controller.current.value
                    if (cur?.id == openedOn && playing.value != expected(cur)) {
                        previewedId = null
                        playChannel(cur)
                    }
                    return@collectLatest
                }
                if (!open) {
                    open = true
                    openedOn = controller.current.value?.id
                    settingsVisible.value = false
                }
                delay(PREVIEW_DELAY_MS)
                val ch = controller.listChannel() ?: return@collectLatest
                viewModelScope.launch { runCatching { simpleTv.guide.touch(ch) } }
                preview(ch)
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
                if (ch.playlist != null && playing.value == ch.id) playQueued(ch, simpleTv.queue.advance(ch))
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
                val id = playing.value ?: return@collectLatest
                when (st) {
                    PlaybackState.PLAYING -> simpleTv.catalog.reportPlayed(id)
                    PlaybackState.ERROR -> {
                        delay(20_000)
                        if (playing.value == id) simpleTv.catalog.reportFailed(id)
                    }
                    else -> Unit
                }
            }
        }
    }

    /** A YouTube playlist channel on this screen: Right, Left and OK move through it like a series. */
    private fun ytPlaylistChannel(): Channel? = controller.current.value?.takeIf {
        it.type == SourceType.YOUTUBE && com.ielali.simpletv.youtube.YouTubeEmbed.isPlaylist(it.url) && !controller.usesExternalPlayer(it)
    }

    /** Where a playlist channel left off, for its player page. */
    fun ytStart(ch: Channel): com.ielali.simpletv.youtube.YouTubeEmbed.Resume? =
        simpleTv.ytProgress.get(ch.id)?.let { com.ielali.simpletv.youtube.YouTubeEmbed.Resume(it.index, it.videoId, it.seconds) }

    /** The playlist page reports what plays (from the WebView's thread). */
    fun onYtReport(channelId: String, index: Int, videoId: String, title: String, seconds: Int, state: Int) {
        viewModelScope.launch {
            if (controller.current.value?.id != channelId) return@launch
            ytPositionMs = seconds * 1000L
            if (title.isNotBlank()) ytNow.value = channelId to title
            if (state == YT_STUCK) { ytProblem.value = channelId; return@launch }
            if (state == YT_PLAYING) { ytPauseAsked = false; paused.value = false; ytProblem.value = null } else if (state == YT_PAUSED && ytPauseAsked) paused.value = true
            if (videoId.isNotBlank() && index >= 0) {
                runCatching { simpleTv.ytProgress.save(channelId, com.ielali.simpletv.data.YouTubeProgress.Spot(index, videoId, seconds, System.currentTimeMillis())) }
            }
        }
    }

    private fun ytAction(a: QueueKeys.Action) {
        val undo = a == QueueKeys.Action.PREVIOUS && skipped?.let { System.currentTimeMillis() - it.third < UNDO_MS } == true
        val cmd = when {
            undo -> "undo"
            else -> ytCmd(a)
        }
        skipped = if (a == QueueKeys.Action.NEXT) Triple("", 0L, System.currentTimeMillis()) else null
        when (cmd) {
            "pause" -> { ytPauseAsked = true; paused.value = true }
            "play" -> { ytPauseAsked = false; paused.value = false }
            "next" -> { ytPauseAsked = false; paused.value = false; teach(TvHint.SKIPPED, UNDO_MS) }
            "prev", "restart", "undo" -> { ytPauseAsked = false; paused.value = false; controller.showBanner() }
        }
        _ytCommands.tryEmit(cmd)
    }

    private fun ytCmd(a: QueueKeys.Action): String {
        return when (a) {
            QueueKeys.Action.NEXT -> "next"
            QueueKeys.Action.PREVIOUS -> "prev"
            QueueKeys.Action.RESTART -> "restart"
            QueueKeys.Action.TOGGLE_PAUSE -> if (paused.value) "play" else "pause"
            QueueKeys.Action.PAUSE -> "pause"
            QueueKeys.Action.PLAY -> "play"
            QueueKeys.Action.FORWARD -> "fwd"
            QueueKeys.Action.BACK -> "back"
        }
    }

    /** Shows a hint line under the banner for [ms]. */
    private fun teach(h: TvHint, ms: Long) {
        hint.value = h
        controller.showBanner(ms)
        hintJob?.cancel()
        hintJob = viewModelScope.launch { delay(ms); hint.value = null }
    }

    /** Once a day, a film, series or playlist channel says how the remote works on it. */
    private fun maybeTeach(ch: Channel) {
        val moves = ch.playlist != null || (ch.type == SourceType.YOUTUBE && com.ielali.simpletv.youtube.YouTubeEmbed.isPlaylist(ch.url))
        if (!moves) { hint.value = null; return }
        val prefs = getApplication<Application>().getSharedPreferences("hints", android.content.Context.MODE_PRIVATE)
        val today = (System.currentTimeMillis() / 86_400_000L).toInt()
        if (prefs.getInt("controls_day", -1) == today) return
        prefs.edit().putInt("controls_day", today).apply()
        teach(TvHint.CONTROLS, 10_000L)
    }

    /** What the player should be playing for [ch]: its id when it plays on this screen. */
    private fun expected(ch: Channel?): String? = if (ch != null && ch.playsOnSimpleTv) ch.id else null

    private fun leaveQueueItem() {
        queueEmpty.value = null
        emptyRetry?.cancel()
        queueItem = null
        paused.value = false
        simpleTv.queue.report(null)
    }

    /** Plays [ch] as the channel being watched: a stream, or a film/series channel where it left off. */
    private suspend fun playChannel(ch: Channel?) {
        leaveQueueItem()
        playing.value = expected(ch)
        when {
            ch == null -> engine.stop()
            ch.type == SourceType.STREAM -> engine.play(ch)
            ch.playlist != null && ch.playsOnSimpleTv -> playQueued(ch, simpleTv.queue.current(ch))
            else -> engine.stop()
        }
    }

    /**
     * The list's preview window: a live channel plays in it; resting on the channel being watched brings
     * it back; YouTube, film and series channels show their logo instead (nothing plays).
     */
    private suspend fun preview(ch: Channel) {
        val cur = controller.current.value
        if (ch.id == cur?.id) {
            if (playing.value != expected(cur)) { previewedId = null; playChannel(cur) }
            return
        }
        if (ch.type == SourceType.STREAM) {
            if (playing.value == ch.id) return
            saveQueueProgress()
            leaveQueueItem()
            previewedId = ch.id
            playing.value = ch.id
            engine.play(ch)
        } else if (playing.value != null) {
            saveQueueProgress()
            leaveQueueItem()
            previewedId = null
            playing.value = null
            engine.stop()
        }
    }

    private fun playQueued(ch: Channel, item: QueuePlayback.Playable?, banner: Boolean = false) {
        setPaused(false)
        emptyRetry?.cancel()
        if (item == null) {
            engine.stop()
            simpleTv.queue.report(null)
            val spec = ch.playlist
            queueEmpty.value = if (spec != null && (com.ielali.simpletv.data.RatingRules.hasRules(spec) || spec.skipNoYear))
                QueueEmpty.CHECKING else QueueEmpty.NOTHING
            // Films get checked in the background: look again every minute while this channel is on.
            emptyRetry = viewModelScope.launch {
                while (true) {
                    delay(60_000)
                    if (controller.current.value?.id != ch.id) return@launch
                    simpleTv.queue.current(ch)?.let { playQueued(ch, it); return@launch }
                }
            }
            return
        }
        queueEmpty.value = null
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
            QueueKeys.Action.NEXT -> viewModelScope.launch {
                queueItem?.second?.let { skipped = Triple(it, engine.positionMs(), System.currentTimeMillis()) }
                playQueued(ch, simpleTv.queue.advance(ch))
                teach(TvHint.SKIPPED, UNDO_MS)
            }
            QueueKeys.Action.PREVIOUS -> viewModelScope.launch {
                val s = skipped?.takeIf { System.currentTimeMillis() - it.third < UNDO_MS && it.first.isNotEmpty() }
                skipped = null
                if (s != null) {
                    simpleTv.queue.save(ch.id, s.first, s.second)
                    playQueued(ch, simpleTv.queue.current(ch), banner = true)
                } else {
                    playQueued(ch, simpleTv.queue.back(ch), banner = true)
                }
            }
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
        (keyCode in QueueKeys.KEYS && (queueChannel() != null || ytPlaylistChannel() != null)) || when (keyCode) {
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
        if (ytPlaylistChannel() != null && controller.tuner.pendingDigits.value.isEmpty()) {
            QueueKeys.action(keyCode, ytPositionMs)?.let { ytAction(it); return true }
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
        /** YouTube player states the playlist page reports. */
        const val YT_PLAYING = 1
        const val YT_PAUSED = 2
        /** Sent by the playlist page when videos fail one after another. */
        const val YT_STUCK = -2
        /** How long Left brings back what Right just skipped. */
        const val UNDO_MS = 5_000L
        /** How long the highlight rests on a channel before the preview window tunes it. */
        const val PREVIEW_DELAY_MS = 1_500L
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
