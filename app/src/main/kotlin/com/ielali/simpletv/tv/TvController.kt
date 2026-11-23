package com.ielali.simpletv.tv

import com.ielali.simpletv.data.AppSettings
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.ChannelList
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.data.YouTubePlayback
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
) {
    val tuner = ChannelTuner(scope, entryTimeoutMs)

    private val _current = MutableStateFlow<Channel?>(null)
    val current: StateFlow<Channel?> = _current.asStateFlow()

    private val _appVisible = MutableStateFlow(false)
    val appVisible: StateFlow<Boolean> = _appVisible.asStateFlow()

    private val _externalUnavailable = MutableStateFlow(false)
    val externalUnavailable: StateFlow<Boolean> = _externalUnavailable.asStateFlow()

    private val bannerShown = MutableStateFlow(false)
    val bannerVisible: StateFlow<Boolean> = combine(bannerShown, tuner.pendingDigits) { shown, digits ->
        shown || digits.isNotEmpty()
    }.stateIn(scope, SharingStarted.Eagerly, false)

    private var bannerJob: Job? = null
    private var lastExternalOpen: Long? = null

    val sortedChannels: List<Channel> get() = channelList.value.sorted()

    init {
        scope.launch { tuner.tuneRequests.collect { switchTo(ChannelNavigator.resolve(it, sortedChannels)) } }
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
    fun onRemoteKey(keyCode: Int): Boolean = when (keyCode) {
        in GuardKeys.DIGIT_0..GuardKeys.DIGIT_9 -> { tuner.onDigit(keyCode - GuardKeys.DIGIT_0); true }
        in GuardKeys.NUMPAD_0..GuardKeys.NUMPAD_9 -> { tuner.onDigit(keyCode - GuardKeys.NUMPAD_0); true }
        GuardKeys.CHANNEL_UP, GuardKeys.DPAD_UP, GuardKeys.PAGE_UP -> { channelUp(); true }
        GuardKeys.CHANNEL_DOWN, GuardKeys.DPAD_DOWN, GuardKeys.PAGE_DOWN -> { channelDown(); true }
        GuardKeys.DPAD_CENTER, GuardKeys.ENTER, GuardKeys.NUMPAD_ENTER -> {
            if (!tuner.onConfirm()) showBanner()
            true
        }
        GuardKeys.INFO, GuardKeys.GUIDE, GuardKeys.HOME, GuardKeys.TV -> { showBanner(); true }
        GuardKeys.BACK -> { tuner.onCancel(); true } // never leave the TV by accident
        else -> false
    }

    fun channelUp() = switchTo(ChannelNavigator.next(_current.value, sortedChannels))
    fun channelDown() = switchTo(ChannelNavigator.previous(_current.value, sortedChannels))

    /** MainActivity reports when its screen starts and stops being visible. */
    fun onAppVisible(visible: Boolean) {
        _appVisible.value = visible
        if (!visible) return
        val ch = _current.value
        // Back on Simple TV's screen while the channel lives in YouTube (boot, or YouTube closed itself):
        // reopen it, but not in a tight loop if YouTube keeps bouncing back.
        if (usesExternalPlayer(ch) && ch != null) {
            val last = lastExternalOpen
            if (last == null || clock() - last >= reopenGraceMs) openExternal(ch)
        }
    }

    fun switchTo(channel: Channel?, launch: Boolean = true) {
        _externalUnavailable.value = false
        _current.value = channel
        if (channel == null) {
            GuardState.allowedExternalPackages = emptySet()
            return
        }
        lastChannel.put(channel.number)
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
                GuardState.allowedExternalPackages = if (usesExternalPlayer(restored)) YOUTUBE_PACKAGES else emptySet()
            }
            sorted.none { it.id == cur.id } -> switchTo(ChannelNavigator.resolve(cur.number, sorted), launch = watching)
            else -> sorted.first { it.id == cur.id }.takeIf { it != cur }?.let { switchTo(it, launch = watching) }
        }
    }

    companion object {
        val YOUTUBE_PACKAGES = setOf(HomeGuardPolicy.YOUTUBE_TV, HomeGuardPolicy.YOUTUBE_MOBILE)
    }
}
