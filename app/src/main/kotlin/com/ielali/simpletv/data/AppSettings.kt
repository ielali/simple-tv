package com.ielali.simpletv.data

import kotlinx.serialization.Serializable

/** How YOUTUBE channels are played. */
@Serializable
enum class YouTubePlayback {
    /**
     * Official IFrame player inside the app's WebView. Stays inside Simple TV, remote keeps working,
     * but it is anonymous: no Premium (so ads), no age-restricted or members-only streams.
     */
    EMBEDDED,

    /**
     * Hand the channel to the YouTube app installed on the box. It plays with the Google account
     * signed in on the TV (Premium, subscriptions, restrictions all apply). While it is in front,
     * the remote drives YouTube, not Simple TV; BACK returns.
     */
    YOUTUBE_APP,
}

/** How big the corner clock is on the TV: 32sp or 48sp. */
@Serializable
enum class ClockSize { SMALL, LARGE }

@Serializable
data class AppSettings(
    val youtubePlayback: YouTubePlayback = YouTubePlayback.EMBEDDED,
    /** Home guard wanted. When true the app re-enables its accessibility service at start and boot. */
    val guardEnabled: Boolean = true,
    /** Epoch millis (TV clock) until which the guard steps aside so a caregiver can use Google TV. */
    val guardPausedUntil: Long = 0L,
    /** Extra remote key codes to swallow, found with the key log on the config page. */
    val guardBlockedKeys: List<Int> = emptyList(),
    /** Remote buttons that open the channel list on the TV (ADR-022). Live TV (TV or GUIDE) by default. */
    val listButtons: List<Int> = com.ielali.simpletv.guard.ButtonRoles.DEFAULT_LIST,
    /** Remote buttons that go back to the previous channel. A lone 0 always does. */
    val previousButtons: List<Int> = emptyList(),
    /** A clock in the top corner while watching (ADR-024). */
    val showClock: Boolean = true,
    /** The corner clock's size, chosen on the page. */
    val clockSize: ClockSize = ClockSize.SMALL,
    /** Language of Simple TV's screens: "" follows the device, else a key of TvLanguage.SUPPORTED. */
    val language: String = TvLanguage.SYSTEM,
    /** When the TV refreshes every subscription's catalog each day, "HH:mm" in the TV's time zone. */
    val catalogRefreshTime: String = RefreshSchedule.DEFAULT_TIME,
    /** Country whose official film ratings apply ("AU"); "" = the TV's own region. */
    val ratingCountry: String = "",
    /** Show a film's rating next to its title in the TV banner. */
    val ratingOnBanner: Boolean = false,
    /** Never play titles TMDB marks as adult, on any film channel. */
    val hideAdult: Boolean = true,
    /** Free TV guides (preset ids) and guide links to use; null = the default for the TV's region. */
    val guideSources: List<String>? = null,
    /** How long the page's preview player plays a channel, in minutes of picture; 0 = until it is closed (ADR-029). */
    val previewMinutes: Int = PreviewSessions.DEFAULT_MINUTES,
)
