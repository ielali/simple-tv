package com.ielali.simpletv.guard

enum class KeyAction {
    PASS,
    GO_HOME,
    SWALLOW,
    /** Send to TvController: channel control while the YouTube app is in front. */
    ROUTE,
    /** A button the caregiver gave the channel list (ADR-022): open or close it, from any screen. */
    CHANNEL_LIST,
    /** A button the caregiver gave "previous channel". */
    PREVIOUS_CHANNEL,
}
enum class WindowAction { NONE, GO_HOME }

/** Snapshot of everything the guard needs to decide; built fresh for every event. */
data class GuardConfig(
    val enabled: Boolean = true,
    val pausedUntil: Long = 0L,
    val extraBlockedKeys: Set<Int> = emptySet(),
    /** Apps Simple TV opened on purpose (YouTube hand-off); they may stay in front. */
    val allowedExternalPackages: Set<String> = emptySet(),
    /** Buttons that open the channel list, and that go back to the previous channel (ButtonRoles). */
    val listKeys: Set<Int> = ButtonRoles.DEFAULT_LIST.toSet(),
    val previousKeys: Set<Int> = emptySet(),
)

/**
 * Decides what the accessibility guard does with each key and window change.
 *
 * Idea credit: tv-vibehack (MIT) — github.com/toomramcharan/tv-vibehack. The stock Google TV home is
 * never disabled; it is covered instead, because disabling it can send Android 14 Google TV into a
 * RescueParty recovery loop.
 */
object HomeGuardPolicy {
    const val OWN_PACKAGE = "com.ielali.simpletv"
    const val STOCK_LAUNCHER = "com.google.android.apps.tv.launcherx"
    const val YOUTUBE_TV = "com.google.android.youtube.tv"
    const val YOUTUBE_MOBILE = "com.google.android.youtube"

    /** Apps reachable by sponsored remote buttons. Bounced back to Simple TV unless Simple TV opened them. */
    val YOUTUBE_PACKAGES: Set<String> = setOf(YOUTUBE_TV, YOUTUBE_MOBILE)

    val SPONSORED_APPS: Set<String> = setOf(
        "com.netflix.ninja",
        "com.amazon.amazonvideo.livingroom",
        "com.disney.disneyplus",
        YOUTUBE_TV,
    )

    /** Window packages the accessibility service subscribes to (keeps it idle otherwise). */
    val WATCHED_PACKAGES: Set<String> = SPONSORED_APPS + STOCK_LAUNCHER

    fun isActive(config: GuardConfig, now: Long): Boolean = config.enabled && now >= config.pausedUntil

    fun onKey(keyCode: Int, config: GuardConfig, now: Long): KeyAction = when {
        !isActive(config, now) -> KeyAction.PASS
        keyCode in config.listKeys -> KeyAction.CHANNEL_LIST
        keyCode in config.previousKeys -> KeyAction.PREVIOUS_CHANNEL
        keyCode in GuardKeys.GO_HOME -> KeyAction.GO_HOME
        keyCode in GuardKeys.PROTECTED -> KeyAction.PASS
        keyCode in GuardKeys.DEFAULT_BLOCKED || keyCode in config.extraBlockedKeys -> KeyAction.SWALLOW
        else -> KeyAction.PASS
    }

    /**
     * Keys while the current channel plays in the YouTube app. Channel keys change channel without
     * passing through Simple TV's screen; BACK and MENU do nothing, so the viewer cannot wander into
     * YouTube's browse screens; OK, left/right and media keys still control the video.
     */
    fun onKeyDuringExternal(keyCode: Int, config: GuardConfig, now: Long, digitsPending: Boolean): KeyAction {
        if (!isActive(config, now)) return KeyAction.PASS
        if (keyCode in config.listKeys) return KeyAction.CHANNEL_LIST
        if (keyCode in config.previousKeys) return KeyAction.PREVIOUS_CHANNEL
        return when (keyCode) {
            in GuardKeys.DIGIT_0..GuardKeys.DIGIT_9, in GuardKeys.NUMPAD_0..GuardKeys.NUMPAD_9,
            GuardKeys.CHANNEL_UP, GuardKeys.CHANNEL_DOWN, GuardKeys.PAGE_UP, GuardKeys.PAGE_DOWN,
            GuardKeys.DPAD_UP, GuardKeys.DPAD_DOWN,
            GuardKeys.INFO, GuardKeys.GUIDE, GuardKeys.HOME, GuardKeys.TV, GuardKeys.BACK -> KeyAction.ROUTE
            GuardKeys.DPAD_CENTER, GuardKeys.ENTER, GuardKeys.NUMPAD_ENTER ->
                if (digitsPending) KeyAction.ROUTE else KeyAction.PASS
            GuardKeys.MENU, GuardKeys.SETTINGS -> KeyAction.SWALLOW
            else -> onKey(keyCode, config, now)
        }
    }

    fun onWindow(packageName: String?, config: GuardConfig, now: Long): WindowAction = when {
        packageName == null || !isActive(config, now) -> WindowAction.NONE
        packageName == STOCK_LAUNCHER -> WindowAction.GO_HOME
        packageName in SPONSORED_APPS && packageName !in config.allowedExternalPackages -> WindowAction.GO_HOME
        else -> WindowAction.NONE
    }

    /** Keys a caregiver may add to the block list: never protected keys, never the home keys, no duplicates. */
    fun sanitizeBlockedKeys(keys: List<Int>): List<Int> =
        keys.filter { it > 0 && it !in GuardKeys.PROTECTED && it !in GuardKeys.GO_HOME }.distinct().sorted()

    fun isBlockable(keyCode: Int): Boolean = sanitizeBlockedKeys(listOf(keyCode)).isNotEmpty()
}

/** Drops repeat triggers inside [windowMs] so one key press or window flicker launches Simple TV once. */
class Debouncer(private val windowMs: Long) {
    private var last: Long? = null

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        val prev = last
        if (prev != null && now - prev < windowMs) return false
        last = now
        return true
    }
}

/** Process-wide state shared between MainActivity (which opens YouTube) and the guard service. */
object GuardState {
    @Volatile
    var allowedExternalPackages: Set<String> = emptySet()

    /** When Simple TV last opened the YouTube app; the account-picker helper only acts shortly after. */
    @Volatile
    var externalLaunchedAt: Long = 0L
}
