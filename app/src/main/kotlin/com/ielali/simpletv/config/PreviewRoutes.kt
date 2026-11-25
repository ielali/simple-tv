package com.ielali.simpletv.config

import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.PreviewRules
import com.ielali.simpletv.data.PreviewSessions
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.youtube.YouTubeEmbed
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receive
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondOutputStream
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import java.io.InputStream

/**
 * The page's preview player (ADR-029), in the channel list and in a subscription's own list. A live
 * channel plays in the browser through the TV: the page gets a random token, never the stream's
 * address, and the TV relays what is behind it. YouTube channels get the address of YouTube's own
 * player. The TV always wins: no preview starts while the TV's player holds the same subscription,
 * and one that is running ends when it does.
 *
 * Takes what it needs from the app as functions, so the whole of it runs in JVM tests.
 */
class PreviewRoutes(
    private val channels: () -> List<Channel>,
    /** A live channel in a subscription's list, as the channel it would be in the lineup; null when it is not there. */
    private val catalogChannel: suspend (providerId: String, itemId: String) -> Channel?,
    /** What Telly's own player holds on the TV right now; empty in standby, on YouTube or behind another app. */
    private val onTv: () -> List<Channel>,
    /** How many streams at once a subscription allows, null when its provider does not say. */
    private val streamLimit: (providerId: String) -> Int?,
    /** The setting: minutes of picture a preview may play, 0 for no limit. */
    private val minutes: () -> Int,
    private val asset: (path: String) -> InputStream,
    userAgent: String,
    /** The playback log: how a preview started and why the TV ended it. Never an address or a name. */
    private val log: (String) -> Unit = {},
    val sessions: PreviewSessions = PreviewSessions(),
) {
    private val relay = PreviewRelay(sessions, userAgent, log)

    /** A lineup channel by [channelId], or a channel in a subscription's list by [providerId] and [itemId]. */
    @Serializable data class Start(val channelId: String? = null, val providerId: String? = null, val itemId: String? = null)

    /**
     * [url] plays in a video element; [embed] in a frame (YouTube). [seconds]: how long the picture
     * may play, null when the caregiver chose no limit.
     */
    @Serializable data class Started(val kind: String, val url: String? = null, val embed: String? = null, val seconds: Int? = null)

    /** [playing]: video has reached the browser. [secondsLeft]: until it stops by itself, null with no limit. */
    @Serializable data class State(val active: Boolean, val ended: String? = null, val playing: Boolean = false, val secondsLeft: Int? = null)

    /** The same shape as the page's other error answers ([ConfigServer.ErrorBody]). */
    @Serializable data class Problem(val error: String, val code: String? = null)

    /**
     * Ends a running preview the TV's player now competes with. Call whenever what the TV plays
     * changes, then [sweep] off the main thread.
     */
    fun tvChanged() {
        val running = sessions.active() ?: return
        if (PreviewRules.check(running.channel, onTv(), streamLimit) != PreviewRules.Verdict.OK) {
            sessions.end(PreviewSessions.Ended.TV_NEEDS_IT)
            log("preview: ended, the TV needs the subscription")
        }
    }

    /**
     * Ends a preview whose time is up or whose browser has gone, and lets go of the provider for every
     * preview that has ended. The server runs it every few seconds: a preview with no time limit must
     * not hold a stream after its browser has vanished. Closes sockets, so not for the main thread.
     */
    fun sweep() {
        sessions.active()
        relay.dropEnded()
    }

    private fun state(token: String) = State(
        active = sessions.isLive(token),
        ended = sessions.whyEnded(token)?.name?.lowercase(),
        playing = sessions.hasPicture(token),
        secondsLeft = sessions.secondsLeft(token),
    )

    fun Route.install() {
        post("/api/preview") {
            val ask = call.receive<Start>()
            val channel = when {
                ask.channelId != null -> channels().firstOrNull { it.id == ask.channelId }
                    ?: return@post call.respond(HttpStatusCode.NotFound, Problem("That channel isn't on the TV any more."))
                ask.providerId != null && ask.itemId != null -> catalogChannel(ask.providerId, ask.itemId)
                    ?: return@post call.respond(HttpStatusCode.NotFound, Problem("That channel isn't in the subscription's list any more."))
                else -> return@post call.respond(HttpStatusCode.BadRequest, Problem("Choose a channel to preview."))
            }
            when (channel.type) {
                SourceType.YOUTUBE -> {
                    val target = YouTubeEmbed.parse(channel.url)
                        ?: return@post call.respond(HttpStatusCode.Conflict, Problem("This YouTube channel's link can't be previewed."))
                    call.respond(Started(kind = "youtube", embed = YouTubeEmbed.previewUrl(target)))
                }
                SourceType.STREAM -> {
                    when (PreviewRules.check(channel, onTv(), streamLimit)) {
                        PreviewRules.Verdict.TV_ON_THIS_CHANNEL ->
                            return@post call.respond(HttpStatusCode.Conflict, Problem("The TV is showing this channel now, so it can't play here as well.", "tv"))
                        PreviewRules.Verdict.TV_USES_SUBSCRIPTION ->
                            return@post call.respond(
                                HttpStatusCode.Conflict,
                                Problem("The TV is playing from the same subscription, and a second stream could stop its picture. Try when the TV is off or on another kind of channel.", "tv"),
                            )
                        PreviewRules.Verdict.OK -> Unit
                    }
                    val session = sessions.start(channel, PreviewSessions.limitMs(minutes()))
                    val limit = session.maxMs?.let { "${it / 60_000} min" } ?: "no limit"
                    log("preview: start, ${if (ask.channelId != null) "channel ${channel.number}" else "from a subscription's list"}, $limit")
                    call.respond(
                        Started(kind = "hls", url = "/preview/${session.token}/${PreviewSessions.INDEX}", seconds = session.maxMs?.let { (it / 1000).toInt() }),
                    )
                }
                else -> call.respond(HttpStatusCode.Conflict, Problem("Film and series channels play on the TV only. Use Play on TV."))
            }
        }
        // Whether a preview still runs and how long it has, or why it stopped, for the page to say so.
        get("/api/preview/{token}") {
            val token = call.parameters["token"].orEmpty()
            val now = state(token)
            if (!now.active) withContext(Dispatchers.IO) { relay.dropEnded() }
            call.respond(now)
        }
        // The page closed its preview. By token: closing an old one must not end the one that replaced it.
        delete("/api/preview/{token}") {
            val token = call.parameters["token"].orEmpty()
            sessions.end(token, PreviewSessions.Ended.CLOSED)
            withContext(Dispatchers.IO) { relay.dropEnded() }
            call.respond(state(token))
        }
        // The stream itself. A video element cannot send the page's sign-in, so the random token in the
        // path is the pass: only a signed-in page can get one, and it dies with the preview.
        get("/preview/{token}/{name}") {
            call.response.header(HttpHeaders.CacheControl, "no-store")
            val token = call.parameters["token"].orEmpty()
            val name = call.parameters["name"].orEmpty()
            when (val reply = withContext(Dispatchers.IO) { relay.fetch(token, name) }) {
                is PreviewRelay.Reply.Playlist -> call.respondText(reply.text, HLS)
                is PreviewRelay.Reply.Stream -> try {
                    call.respondOutputStream(contentType(reply.contentType)) { reply.copyTo(this) }
                } finally {
                    // Also when the browser left before anything was sent.
                    reply.close()
                }
                PreviewRelay.Reply.Gone -> call.respond(HttpStatusCode.Gone, Problem("The preview has ended."))
                PreviewRelay.Reply.Failed -> call.respond(HttpStatusCode.BadGateway, Problem("The channel didn't answer."))
                PreviewRelay.Reply.Unplayable ->
                    call.respond(HttpStatusCode.UnsupportedMediaType, Problem("This channel's kind of stream can't play in a browser.", "kind"))
            }
        }
        // The player library browsers other than Safari need for HLS; bundled, never fetched from outside.
        get("/hls.min.js") {
            val bytes = withContext(Dispatchers.IO) { asset("config/hls.min.js").use { it.readBytes() } }
            call.response.header(HttpHeaders.CacheControl, "public, max-age=86400")
            call.respondBytes(bytes, ContentType.Text.JavaScript)
        }
    }

    private fun contentType(raw: String?): ContentType =
        raw?.let { runCatching { ContentType.parse(it) }.getOrNull() } ?: ContentType.Application.OctetStream

    private companion object {
        val HLS = ContentType.parse("application/vnd.apple.mpegurl")
    }
}
