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
 * The preview player in the page's channel list (ADR-029). A live channel plays in the browser through
 * the TV: the page gets a random token, never the stream's address, and the TV relays what is behind
 * it. YouTube channels get the address of YouTube's own player. The TV always wins: no preview starts
 * while the TV's player holds the same subscription, and one that is running ends when it does.
 *
 * Takes what it needs from the app as functions, so the whole of it runs in JVM tests.
 */
class PreviewRoutes(
    private val channels: () -> List<Channel>,
    /** What Telly's own player holds on the TV right now; empty in standby, on YouTube or behind another app. */
    private val onTv: () -> List<Channel>,
    /** How many streams at once a subscription allows, null when its provider does not say. */
    private val streamLimit: (providerId: String) -> Int?,
    private val asset: (path: String) -> InputStream,
    userAgent: String,
    val sessions: PreviewSessions = PreviewSessions(),
) {
    private val relay = PreviewRelay(sessions, userAgent)

    @Serializable data class Start(val channelId: String)

    /** [url] plays in a video element; [embed] in a frame (YouTube). [seconds]: how long the preview may run. */
    @Serializable data class Started(val kind: String, val url: String? = null, val embed: String? = null, val seconds: Int? = null)

    @Serializable data class State(val active: Boolean, val ended: String? = null)

    /** The same shape as the page's other error answers ([ConfigServer.ErrorBody]). */
    @Serializable data class Problem(val error: String, val code: String? = null)

    /** Ends a running preview the TV's player now competes with. Call whenever what the TV plays changes. */
    fun tvChanged() {
        val running = sessions.active() ?: return
        if (PreviewRules.check(running.channel, onTv(), streamLimit) != PreviewRules.Verdict.OK) {
            sessions.end(PreviewSessions.Ended.TV_NEEDS_IT)
        }
    }

    fun Route.install() {
        post("/api/preview") {
            val id = call.receive<Start>().channelId
            val channel = channels().firstOrNull { it.id == id }
                ?: return@post call.respond(HttpStatusCode.NotFound, Problem("That channel isn't on the TV any more."))
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
                    val session = sessions.start(channel)
                    call.respond(Started(kind = "hls", url = "/preview/${session.token}/${PreviewSessions.INDEX}", seconds = (sessions.maxMs / 1000).toInt()))
                }
                else -> call.respond(HttpStatusCode.Conflict, Problem("Film and series channels play on the TV only. Use Play on TV."))
            }
        }
        // Why a preview stopped, for the page to say so.
        get("/api/preview/{token}") {
            val token = call.parameters["token"].orEmpty()
            call.respond(State(active = sessions.isLive(token), ended = sessions.whyEnded(token)?.name?.lowercase()))
        }
        // The page closed its preview. By token: closing an old one must not end the one that replaced it.
        delete("/api/preview/{token}") {
            val token = call.parameters["token"].orEmpty()
            sessions.end(token, PreviewSessions.Ended.CLOSED)
            call.respond(State(active = false, ended = sessions.whyEnded(token)?.name?.lowercase()))
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
