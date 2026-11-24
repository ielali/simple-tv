package com.ielali.simpletv.tv

import com.ielali.simpletv.data.AppSettings
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.ChannelList
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.data.YouTubePlayback
import com.ielali.simpletv.guard.ButtonRoles
import com.ielali.simpletv.guard.GuardKeys
import com.ielali.simpletv.guard.GuardState
import com.ielali.simpletv.guard.HomeGuardPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Opens channels outside Simple TV (the YouTube app) and brings Simple TV back. Android side: ExternalPlayer. */
interface ExternalLauncher {
    /** Returns false when no app can play the channel. */
    fun open(channel: Channel): Boolean
    fun bringAppToFront()
}

interface LastChannelStore {
    fun get(): Int?
    fun put(number: Int)
}

/**
 * The TV itself: which channel is on, digit entry, the banner, and whether the channel plays inside
 * Simple TV or in the YouTube app. App-scoped, so it keeps working while the YouTube app is in front:
 * the home guard routes remote keys here, and YouTube-to-YouTube changes never show Simple TV's screen.
 *
 * Android-free so it is unit tested on the JVM; the activity and the guard are thin adapters.
 */
class TvController(
    private val scope: CoroutineScope,
    private val channelList: StateFlow<ChannelList>,
    private val settings: StateFlow<AppSettings>,
    private val launcher: ExternalLauncher,
    private val lastChannel: LastChannelStore,
    private val clock: () -> Long = System::currentTimeMillis,
    entryTimeoutMs: Long = 1500L,
    private val bannerMs: Long = 3500L,
    private val reopenGraceMs: Long = 5000L,
    private val listIdleMs: Long = 30_000L,
) {
    val tuner = ChannelTuner(scope, entryTimeoutMs)

    private val _current = MutableStateFlow<Channel?>(null)
    val current: StateFlow<Channel?> = _current.asStateFlow()

    private val _appVisible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    /** When the current channel started, and whether the caregiver started it from the config page. */
    @Volatile var since: Long = 0L
        private set
    @Volatile var startedFromApp: Boolean = false
        private set

    private val _externalUnavailable = MutableStateFlow(false)
    val externalUnavailable: StateFlow<Boolean> = _externalUnavailable.asStateFlow()

    private val bannerShown = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = combine(bannerShown, tuner.pendingDigits) { shown, digits ->
        shown || digits.isNotEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var bannerJob: Job? = null
    private var lastExternalOpen: Long? = null

    /** The channel list (ADR-022): the highlighted row, or null while it is closed. */
    private val _listIndex = MutableStateFlow<Int?>(null)
    val listIndex: StateFlow<Int?> = _listIndex.asStateFlow()
    private var listJob: Job? = null

    /** Rows the screen fits in the list; a page up or down moves this many. Set by the screen. */
    @Volatile var listPage: Int = 5

    /** The channel watched before this one, for "previous channel" (a lone 0, or a button the caregiver chose). */
    @Volatile private var previousId: String? = null

    val sortedChannels: List<Channel> get() = channelList.value.sorted()

    init {
        scope.launch {
            tuner.tuneRequests.collect { n ->
                // A lone 0 (there is no channel 0) goes back to the previous channel, like a recall button.
                if (n != 0 || !previousChannel()) switchTo(ChannelNavigator.resolve(n, sortedChannels))
            }
        }
        scope.launch { channelList.collect { reconcile(it.sorted()) } }
        // Switching the YouTube mode on the config page re-applies the current channel.
        scope.launch {
            settings.map { it.youtubePlayback }.distinctUntilChanged().drop(1).collect {
                _current.value?.let { ch -> switchTo(ch, launch = _appVisible.value || isExternalFront()) }
            }
        }
    }

    fun usesExternalPlayer(channel: Channel?): Boolean =
        channel?.type == SourceType.YOUTUBE &&
            settings.value.youtubePlayback == YouTubePlayback.YOUTUBE_APP &&
            !_externalUnavailable.value

    /** True while the viewer watches the current channel in the YouTube app rather than Simple TV. */
    fun isExternalFront(): Boolean = !_appVisible.value && usesExternalPlayer(_current.value)

    /**
     * Remote keys for channel control, from Simple TV's screen or (via the guard) from over YouTube.
     * Returns true when consumed. MENU/SETTINGS are screen-specific and handled by the caller.
     */
    fun onRemoteKey(keyCode: Int): Boolean = if (_listIndex.value != null) onListKey(keyCode) else when (keyCode) {
        in GuardKeys.DIGIT_0..GuardKeys.DIGIT_9 -> { tuner.onDigit(keyCode - GuardKeys.DIGIT_0); true }
        in GuardKeys.NUMPAD_0..GuardKeys.NUMPAD_9 -> { tuner.onDigit(keyCode - GuardKeys.NUMPAD_0); true }
        GuardKeys.CHANNEL_UP, GuardKeys.DPAD_UP, GuardKeys.PAGE_UP -> { channelUp(); true }
        GuardKeys.CHANNEL_DOWN, GuardKeys.DPAD_DOWN, GuardKeys.PAGE_DOWN -> { channelDown(); true }
        GuardKeys.DPAD_CENTER, GuardKeys.ENTER, GuardKeys.NUMPAD_ENTER -> {
            if (!tuner.onConfirm()) openList() // OK while watching opens the channel list, as on most TVs
            true
        }
        GuardKeys.INFO, GuardKeys.GUIDE, GuardKeys.HOME, GuardKeys.TV -> { showBanner(); true }
        GuardKeys.BACK -> { tuner.onCancel(); true } // never leave the TV by accident
        else -> false
    }

    /** The job the caregiver gave this button on the config page, if any. */
    fun roleOf(keyCode: Int): ButtonRoles.Role? =
        settings.value.let { ButtonRoles.roleOf(keyCode, it.listButtons, it.previousButtons) }

    fun isListOpen(): Boolean = _listIndex.value != null

    /** The list button: opens the list on the channel being watched, or closes it. */
    fun toggleList() = if (isListOpen()) closeList() else openList()

    fun openList() {
        val sorted = sortedChannels
        if (sorted.isEmpty()) return
        tuner.onCancel()
        bannerJob?.cancel()
        bannerShown.value = false
        _listIndex.value = ChannelListNav.start(_current.value, sorted)
        armList()
        // Over YouTube or another app: Simple TV comes forward to show the list.
        if (!_appVisible.value) launcher.bringAppToFront()
    }

    /** Closes the list without changing channel; a channel that lives in the YouTube app goes back there. */
    fun closeList() {
        if (_listIndex.value == null) return
        listJob?.cancel()
        _listIndex.value = null
        val ch = _current.value
        if (_appVisible.value && ch != null && usesExternalPlayer(ch)) openExternal(ch)
    }

    /** A key while the list is open. Returns false for keys that are not the list's (volume, power...). */
    fun onListKey(keyCode: Int): Boolean {
        val index = _listIndex.value ?: return false
        val sorted = sortedChannels
        return when (val step = ChannelListNav.onKey(keyCode, index, sorted.size, listPage.coerceAtLeast(1))) {
            is ChannelListNav.Step.Highlight -> { _listIndex.value = step.index; armList(); true }
            is ChannelListNav.Step.Watch -> {
                listJob?.cancel()
                _listIndex.value = null
                switchTo(sorted[step.index])
                true
            }
            is ChannelListNav.Step.Digit -> { closeList(); tuner.onDigit(step.digit); true }
            ChannelListNav.Step.Close -> { closeList(); true }
            ChannelListNav.Step.Ignore -> { armList(); true }
            ChannelListNav.Step.Pass -> false
        }
    }

    /** The highlighted channel while the list is open. */
    fun listChannel(): Channel? = _listIndex.value?.let { sortedChannels.getOrNull(it) }

    private fun armList() {
        listJob?.cancel()
        listJob = scope.launch {
            delay(listIdleMs)
            closeList() // left alone: the list goes away by itself
        }
    }

    /** Back to the channel watched before this one. False when there is none. */
    fun previousChannel(): Boolean {
        val prev = previousId?.let { id -> sortedChannels.firstOrNull { it.id == id } } ?: run { showBanner(); return false }
        if (isListOpen()) { listJob?.cancel(); _listIndex.value = null }
        switchTo(prev)
        return true
    }

    fun channelUp() = switchTo(ChannelNavigator.next(_current.value, sortedChannels))
    fun channelDown() = switchTo(ChannelNavigator.previous(_current.value, sortedChannels))

    /** MainActivity reports when its screen starts and stops being visible. */
    fun onAppVisible(visible: Boolean) {
        _appVisible.value = visible
        if (!visible || _listIndex.value != null) return // the list is open over the channel
        val ch = _current.value
        // Back on Simple TV's screen while the channel lives in YouTube (boot, or YouTube closed itself):
        // reopen it, but not in a tight loop if YouTube keeps bouncing back.
        if (usesExternalPlayer(ch) && ch != null) {
            val last = lastExternalOpen
            if (last == null || clock() - last >= reopenGraceMs) openExternal(ch)
        }
    }

    /**
     * "Play on TV now" from the config page: a lineup channel, or a [preview] that is not in the lineup
     * (number 0), which is played but never remembered as the last channel.
     */
    fun playFromApp(channel: Channel) {
        switchTo(channel, launch = true, fromApp = true)
    }

    fun switchTo(channel: Channel?, launch: Boolean = true, fromApp: Boolean = false) {
        _externalUnavailable.value = false
        val old = _current.value
        if (old != null && old.number > 0 && channel?.id != old.id) previousId = old.id
        if (channel?.id != _current.value?.id || fromApp) {
            since = clock()
            startedFromApp = fromApp
        }
        _current.value = channel
        if (channel == null) {
            GuardState.allowedExternalPackages = emptySet()
            return
        }
        if (channel.number > 0) lastChannel.put(channel.number)
        if (usesExternalPlayer(channel)) {
            GuardState.allowedExternalPackages = YOUTUBE_PACKAGES
            if (launch) openExternal(channel)
        } else {
            GuardState.allowedExternalPackages = emptySet()
            // Leaving YouTube for a stream or embed: Simple TV's screen has to come forward.
            if (launch && !_appVisible.value) launcher.bringAppToFront()
        }
        showBanner()
    }

    private fun openExternal(channel: Channel) {
        GuardState.allowedExternalPackages = YOUTUBE_PACKAGES
        lastExternalOpen = clock()
        GuardState.externalLaunchedAt = clock()
        if (!launcher.open(channel)) {
            _externalUnavailable.value = true // the screen falls back to the embedded player
            GuardState.allowedExternalPackages = emptySet()
            if (!_appVisible.value) launcher.bringAppToFront()
        }
    }

    fun showBanner() {
        bannerJob?.cancel()
        bannerShown.value = true
        bannerJob = scope.launch {
            delay(bannerMs)
            bannerShown.value = false
        }
    }

    private fun reconcile(sorted: List<Channel>) {
        val cur = _current.value
        // Only touch the screen or YouTube when the viewer is actually watching.
        val watching = _appVisible.value || isExternalFront()
        when {
            sorted.isEmpty() -> switchTo(null)
            cur == null -> {
                // First load: pick the last-watched channel silently; onAppVisible starts playback.
                val restored = ChannelNavigator.resolve(lastChannel.get() ?: sorted.first().number, sorted)
                _current.value = restored
                since = clock()
                GuardState.allowedExternalPackages = if (usesExternalPlayer(restored)) YOUTUBE_PACKAGES else emptySet()
            }
            cur.number == 0 -> Unit // a "Play on TV" preview stays on until the viewer changes channel
            sorted.none { it.id == cur.id } -> switchTo(ChannelNavigator.resolve(cur.number, sorted), launch = watching)
            else -> sorted.first { it.id == cur.id }.takeIf { it != cur }?.let { switchTo(it, launch = watching) }
        }
    }

    companion object {
        val YOUTUBE_PACKAGES = setOf(HomeGuardPolicy.YOUTUBE_TV, HomeGuardPolicy.YOUTUBE_MOBILE)
    }
}
