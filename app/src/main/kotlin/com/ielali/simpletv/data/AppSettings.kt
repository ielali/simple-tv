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

@Serializable
data class AppSettings(
    val youtubePlayback: YouTubePlayback = YouTubePlayback.EMBEDDED,
)
