package com.ielali.simpletv.data

import kotlinx.serialization.Serializable

/** How a channel is sourced. Keep this list short: every type needs a player implementation. */
@Serializable
enum class SourceType {
    /** Any URL Media3/ExoPlayer can play: HLS (.m3u8), DASH (.mpd), MPEG-TS, MP4. */
    STREAM,

    /** A YouTube live stream, played through the official embedded player in a WebView. */
    YOUTUBE,
}

/**
 * One entry on the remote's channel list.
 *
 * [number] is what the viewer types on the remote. Numbers are unique and drive sort order.
 * [url] meaning depends on [type]:
 *  - STREAM: the stream URL.
 *  - YOUTUBE: either a video id, a full watch/live URL, or a channel id (UC...) for "current live stream of this channel".
 */
@Serializable
data class Channel(
    val id: String,
    val number: Int,
    val name: String,
    val type: SourceType = SourceType.STREAM,
    val url: String,
    val logoUrl: String? = null,
    val group: String? = null,
    /** Optional HTTP headers some IPTV providers require (User-Agent, Referer). */
    val headers: Map<String, String> = emptyMap(),
)

@Serializable
data class ChannelList(
    val version: Int = 1,
    val channels: List<Channel> = emptyList(),
) {
    fun sorted(): List<Channel> = channels.sortedBy { it.number }
}
