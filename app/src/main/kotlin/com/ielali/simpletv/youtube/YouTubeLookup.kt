package com.ielali.simpletv.youtube

import com.ielali.simpletv.data.TextFetcher
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.net.URLEncoder

/**
 * Preview for "Add from YouTube" (ADR-014). Video and live links use YouTube's public oEmbed endpoint,
 * no key needed. Channel names, `@handle` links and "Live now" need the household's optional YouTube
 * Data API key, which the TV sends in the X-Goog-Api-Key header (never in a URL) and never returns.
 */
class YouTubeLookup(private val fetcher: TextFetcher) {

    @Serializable
    data class Preview(
        /** What to store as the channel's source: a channel id (live stream) or a watch link. */
        val source: String,
        val kind: String, // "video", "channel" or "playlist"
        val title: String? = null,
        val thumbnail: String? = null,
        val author: String? = null,
        /** null when unknown (no key). */
        val live: Boolean? = null,
        val channelId: String? = null,
        /** Playlists: how many videos (needs the key), and the video the link started at, if any. */
        val count: Int? = null,
        val listId: String? = null,
        val startVideo: String? = null,
    )

    sealed interface Result {
        data class Found(val preview: Preview) : Result
        /** Caregiver-readable reason; [code] lets the page offer the right next step. */
        data class Problem(val code: String, val message: String) : Result
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun preview(input: String, apiKey: String?): Result {
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return problem(INVALID, "Paste a YouTube link or a channel ID.")
        YouTubeEmbed.handleOf(trimmed)?.let { handle ->
            if (apiKey.isNullOrBlank()) {
                return problem(NEEDS_KEY, "Links that start with @ need a YouTube API key. Add one in TV settings, or paste the channel's link instead.")
            }
            val id = when (val r = channelIdForHandle(handle, apiKey)) {
                is Lookup.Ok -> r.value
                is Lookup.Fail -> return r.problem
            } ?: return problem(NOT_FOUND, "We couldn't find $handle on YouTube.")
            return channel(id, apiKey)
        }
        return when (val target = YouTubeEmbed.parse(trimmed)) {
            null -> problem(INVALID, "That doesn't look like a YouTube link or channel ID.")
            is YouTubeEmbed.Target.ChannelLive -> channel(target.channelId, apiKey)
            is YouTubeEmbed.Target.Video -> video(target.id, apiKey)
            is YouTubeEmbed.Target.Playlist -> playlist(target, apiKey)
        }
    }

    /** A playlist's name from oEmbed (no key); with the key, also how many videos and its picture. */
    private fun playlist(target: YouTubeEmbed.Target.Playlist, apiKey: String?): Result {
        val page = "https://www.youtube.com/playlist?list=${target.listId}"
        val o = (get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(page, "UTF-8"), null) as? Lookup.Ok)?.value?.let(::obj)
        var preview = Preview(
            source = YouTubeEmbed.playlistSource(target.listId, target.startVideo, target.shuffle), kind = "playlist",
            title = o.str("title"), thumbnail = o.str("thumbnail_url"), author = o.str("author_name"),
            listId = target.listId, startVideo = target.startVideo,
        )
        if (!apiKey.isNullOrBlank()) {
            when (val r = get("$API/playlists?part=snippet,contentDetails&id=${target.listId}", apiKey)) {
                is Lookup.Fail -> return r.problem
                is Lookup.Ok -> {
                    val item = (obj(r.value)?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject
                        ?: return problem(NOT_FOUND, "That playlist isn't on YouTube, or it's private.")
                    val snippet = item["snippet"] as? JsonObject
                    val thumb = ((snippet?.get("thumbnails") as? JsonObject)?.let { t -> (t["high"] ?: t["medium"] ?: t["default"]) as? JsonObject }).str("url")
                    preview = preview.copy(
                        title = preview.title ?: snippet.str("title"),
                        author = preview.author ?: snippet.str("channelTitle"),
                        thumbnail = preview.thumbnail ?: thumb,
                        count = (item["contentDetails"] as? JsonObject).str("itemCount")?.toIntOrNull(),
                    )
                }
            }
        }
        // Without a key and no answer from oEmbed the name is unknown; the playlist may still play.
        return Result.Found(preview)
    }

    private fun video(id: String, apiKey: String?): Result {
        val watch = "https://www.youtube.com/watch?v=$id"
        val oembed = get("https://www.youtube.com/oembed?format=json&url=" + URLEncoder.encode(watch, "UTF-8"), null)
        val o = (oembed as? Lookup.Ok)?.value?.let(::obj)
        var preview = Preview(
            source = watch, kind = "video",
            title = o.str("title"), thumbnail = o.str("thumbnail_url"), author = o.str("author_name"),
        )
        if (!apiKey.isNullOrBlank()) {
            when (val r = get("$API/videos?part=snippet&id=$id", apiKey)) {
                is Lookup.Fail -> return r.problem
                is Lookup.Ok -> {
                    val snippet = (obj(r.value)?.get("items") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("snippet") as? JsonObject }
                        ?: return problem(NOT_FOUND, "That video isn't on YouTube, or it's private.")
                    preview = preview.copy(
                        title = preview.title ?: snippet.str("title"),
                        author = preview.author ?: snippet.str("channelTitle"),
                        live = snippet.str("liveBroadcastContent") == "live",
                        channelId = snippet.str("channelId"),
                    )
                }
            }
        } else if (oembed is Lookup.Fail && o == null) {
            // oEmbed refuses some videos (embedding off, private); the link may still play in the YouTube app.
            preview = preview.copy(title = null)
        }
        return Result.Found(preview)
    }

    private fun channel(id: String, apiKey: String?): Result {
        if (apiKey.isNullOrBlank()) return Result.Found(Preview(source = id, kind = "channel", channelId = id))
        val snippet = when (val r = get("$API/channels?part=snippet&id=$id", apiKey)) {
            is Lookup.Fail -> return r.problem
            is Lookup.Ok -> (obj(r.value)?.get("items") as? JsonArray)?.firstOrNull()?.let { (it as? JsonObject)?.get("snippet") as? JsonObject }
        } ?: return problem(NOT_FOUND, "We couldn't find that channel on YouTube.")
        val thumb = ((snippet["thumbnails"] as? JsonObject)?.let { t -> (t["high"] ?: t["medium"] ?: t["default"]) as? JsonObject }).str("url")
        // "Live now" costs 100 of the 10,000 free daily units, so it is checked once, when adding.
        val live = when (val r = get("$API/search?part=id&type=video&eventType=live&maxResults=1&channelId=$id", apiKey)) {
            is Lookup.Ok -> ((obj(r.value)?.get("items") as? JsonArray)?.isNotEmpty() == true)
            is Lookup.Fail -> null
        }
        return Result.Found(Preview(source = id, kind = "channel", title = snippet.str("title"), thumbnail = thumb, live = live, channelId = id))
    }

    /**
     * Each YouTube channel's picture, for lineup channels without a logo (ADR-027): live channels by
     * their channel id, videos and playlists by the channel that owns them. Official Data API only,
     * 50 ids per call at 1 quota unit each. [auth] is the API key header or the signed-in account's
     * bearer token. Returns lineup channel id -> picture address; failures just leave channels out.
     */
    fun pictures(targets: Map<String, YouTubeEmbed.Target>, auth: Map<String, String>): Map<String, String> {
        val owner = HashMap<String, String>() // lineup id -> YouTube channel id
        targets.forEach { (id, t) -> if (t is YouTubeEmbed.Target.ChannelLive) owner[id] = t.channelId }
        fun owners(kind: String, ids: Map<String, String>) {
            ids.values.distinct().chunked(50).forEach { chunk ->
                val body = getWith("$API/$kind?part=snippet&id=" + chunk.joinToString(","), auth) ?: return@forEach
                val byId = (obj(body)?.get("items") as? JsonArray).orEmpty().mapNotNull { el ->
                    val o = el as? JsonObject ?: return@mapNotNull null
                    val ch = (o["snippet"] as? JsonObject).str("channelId") ?: return@mapNotNull null
                    (o.str("id") ?: return@mapNotNull null) to ch
                }.toMap()
                ids.forEach { (lineupId, itemId) -> byId[itemId]?.let { owner[lineupId] = it } }
            }
        }
        owners("videos", targets.mapNotNull { (id, t) -> (t as? YouTubeEmbed.Target.Video)?.let { id to it.id } }.toMap())
        owners("playlists", targets.mapNotNull { (id, t) -> (t as? YouTubeEmbed.Target.Playlist)?.let { id to it.listId } }.toMap())
        val pic = HashMap<String, String>()
        owner.values.distinct().chunked(50).forEach { chunk ->
            val body = getWith("$API/channels?part=snippet&id=" + chunk.joinToString(","), auth) ?: return@forEach
            (obj(body)?.get("items") as? JsonArray).orEmpty().forEach { el ->
                val o = el as? JsonObject ?: return@forEach
                val thumbs = (o["snippet"] as? JsonObject)?.get("thumbnails") as? JsonObject
                val url = listOf("high", "medium", "default").firstNotNullOfOrNull { (thumbs?.get(it) as? JsonObject).str("url") }
                if (url != null) o.str("id")?.let { pic[it] = url }
            }
        }
        return owner.mapNotNull { (lineupId, ch) -> pic[ch]?.let { lineupId to it } }.toMap()
    }

    private fun getWith(url: String, auth: Map<String, String>): String? = try {
        fetcher.get(url, auth + ("User-Agent" to "Telly/1 (Android TV)")).takeIf { it.ok }?.body
    } catch (e: IOException) { null }

    private fun channelIdForHandle(handle: String, apiKey: String): Lookup<String?> =
        when (val r = get("$API/channels?part=id&forHandle=" + URLEncoder.encode(handle, "UTF-8"), apiKey)) {
            is Lookup.Fail -> r
            is Lookup.Ok -> Lookup.Ok(((obj(r.value)?.get("items") as? JsonArray)?.firstOrNull() as? JsonObject).str("id"))
        }

    private sealed interface Lookup<out T> {
        data class Ok<T>(val value: T) : Lookup<T>
        data class Fail(val problem: Result.Problem) : Lookup<Nothing>
    }

    private fun get(url: String, apiKey: String?): Lookup<String> {
        val headers = buildMap {
            put("User-Agent", "SimpleTV/0.1 (Android TV)")
            if (!apiKey.isNullOrBlank()) put("X-Goog-Api-Key", apiKey)
        }
        val r = try {
            fetcher.get(url, headers)
        } catch (e: IOException) {
            return Lookup.Fail(problem(OFFLINE, "The TV couldn't reach YouTube. Check its internet connection."))
        }
        if (r.ok) return Lookup.Ok(r.body)
        val reason = r.body.lowercase()
        return Lookup.Fail(
            when {
                "quota" in reason -> problem(QUOTA, "YouTube's daily limit was reached. Try again tomorrow.")
                "api key" in reason || "keyinvalid" in reason || "api_key_invalid" in reason || r.code == 400 && apiKey != null ->
                    problem(KEY_REJECTED, "The YouTube API key was not accepted. Check it in TV settings.")
                r.code == 404 || r.code == 401 -> problem(NOT_FOUND, "That isn't on YouTube, or it's private.")
                else -> problem(OFFLINE, "YouTube didn't answer properly (HTTP ${r.code}). Try again in a moment.")
            }
        )
    }

    private fun obj(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
    private fun JsonObject?.str(key: String): String? = (this?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
    private fun problem(code: String, message: String) = Result.Problem(code, message)

    companion object {
        private const val API = "https://www.googleapis.com/youtube/v3"
        const val INVALID = "invalid"
        const val NEEDS_KEY = "needs_key"
        const val NOT_FOUND = "not_found"
        const val KEY_REJECTED = "key_rejected"
        const val QUOTA = "quota"
        const val OFFLINE = "offline"
    }
}
