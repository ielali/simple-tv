package com.ielali.simpletv.data

import kotlinx.serialization.Serializable

/** How a channel is sourced. Keep this list short: every type needs a player implementation. */
@Serializable
enum class SourceType {
    /** Any URL Media3/ExoPlayer can play: HLS (.m3u8), DASH (.mpd), MPEG-TS, MP4. */
    STREAM,

    /** A YouTube live stream, played through the official embedded player in a WebView. */
    YOUTUBE,

    /** Plays films from a subscription one after another (see [PlaylistSpec]). */
    FILMS,

    /** Plays one series episode by episode and remembers where it stopped (see [PlaylistSpec]). */
    SERIES,
}

@Serializable
enum class PlaylistMode { FILM, SERIES }

/**
 * What a film or series channel plays. The queue is rebuilt from the cached catalog, so it follows
 * the provider's offer; [seed] keeps a shuffled order stable between rebuilds.
 */
@Serializable
data class PlaylistSpec(
    val mode: PlaylistMode,
    val providerId: String,
    /** Film categories to play (FILM mode, when [titles] is empty). */
    val categories: List<String> = emptyList(),
    /** Hand-picked film ids, in play order (FILM mode). */
    val titles: List<String> = emptyList(),
    /** The series to play (SERIES mode). */
    val seriesId: String? = null,
    val shuffle: Boolean = false,
    val seed: Long = 0L,
    /** Item ids the caregiver moved to the front, in order. */
    val first: List<String> = emptyList(),
    /** Item ids the caregiver took out of the queue. */
    val excluded: List<String> = emptyList(),
    /** Subtitles to show: null = off, "any", or a language code ("ar", "en"). Missing = none shown. */
    val subtitles: String? = null,
    /** Soundtrack language; null = as the film comes. Missing = the film's own soundtrack. */
    val audio: String? = null,
)

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
    /** Optional HTTP headers some IPTV providers require (User-Agent, Referer, Authorization). */
    val headers: Map<String, String> = emptyMap(),
    /** Set when the channel was imported from a Provider; lets the list be re-synced or removed together. */
    val providerId: String? = null,
    /** The provider's own id for this channel (Xtream stream id, or a hash of an M3U address). */
    val sourceId: String? = null,
    /** Film and series channels only. */
    val playlist: PlaylistSpec? = null,
)

/** Streams, films and series play on Simple TV's own player (YouTube uses the WebView or the YouTube app). */
val Channel.playsOnSimpleTv: Boolean
    get() = type == SourceType.STREAM || type == SourceType.FILMS || type == SourceType.SERIES

@Serializable
data class ChannelList(
    val version: Int = 1,
    val channels: List<Channel> = emptyList(),
) {
    fun sorted(): List<Channel> = channels.sortedBy { it.number }
}
