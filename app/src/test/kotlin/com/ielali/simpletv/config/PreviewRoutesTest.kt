package com.ielali.simpletv.config

import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.PlaylistMode
import com.ielali.simpletv.data.PlaylistSpec
import com.ielali.simpletv.data.SourceType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * The preview player's routes and relay end to end (ADR-029): a real config-server engine in front, a
 * provider on a local socket behind (java.net.ServerSocket, as in HttpFetcherTest).
 */
class PreviewRoutesTest {

    /** A provider that answers each path from [pages] and remembers what it was asked. */
    private class FakeProvider {
        class Page(val status: String, val body: ByteArray, val headers: List<String> = emptyList())

        val pages = ConcurrentHashMap<String, Page>()
        val asked = CopyOnWriteArrayList<String>()
        val agents = CopyOnWriteArrayList<String>()
        private val socket = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        val port: Int get() = socket.localPort

        init {
            thread(isDaemon = true) {
                while (true) {
                    val client = try { socket.accept() } catch (e: IOException) { break }
                    thread(isDaemon = true) { runCatching { serve(client) } }
                }
            }
        }

        private fun serve(client: Socket) = client.use { c ->
            val reader = c.getInputStream().bufferedReader()
            val path = (reader.readLine() ?: return@use).split(' ').getOrElse(1) { "/" }
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("User-Agent:", ignoreCase = true)) agents += line.substringAfter(':').trim()
            }
            asked += path
            val page = pages[path] ?: Page("404 Not Found", "no such stream at $path".toByteArray())
            c.getOutputStream().apply {
                val extra = page.headers.joinToString("") { "$it\r\n" }
                write("HTTP/1.1 ${page.status}\r\n${extra}Content-Length: ${page.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(page.body)
                flush()
            }
        }

        fun close() = socket.close()
    }

    private class Answer(val status: Int, val type: String?, val body: ByteArray) {
        val text: String get() = body.toString(Charsets.UTF_8)
    }

    private val json = Json { ignoreUnknownKeys = true }
    private val provider = FakeProvider()
    private val segment = ByteArray(200_000) { (it * 31 % 251).toByte() }.also { it[0] = 0x47 }

    private val live = Channel(
        id = "ch41", number = 41, name = "News", url = "http://127.0.0.1:${provider.port}/live/alice/s3cret/41.m3u8",
        headers = mapOf("User-Agent" to "UA-test"), providerId = "xtream",
    )
    private val other = live.copy(id = "ch42", number = 42, name = "Sport", url = "http://127.0.0.1:${provider.port}/live/alice/s3cret/42.m3u8")
    private val youtube = Channel(id = "yt", number = 3, name = "Clips", type = SourceType.YOUTUBE, url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ")
    private val films = Channel(id = "f", number = 4, name = "Films", type = SourceType.FILMS, url = "", playlist = PlaylistSpec(PlaylistMode.FILM, "xtream"))

    @Volatile private var onTv: List<Channel> = emptyList()
    private val routes = PreviewRoutes(
        channels = { listOf(live, other, youtube, films) },
        onTv = { onTv },
        streamLimit = { if (it == "xtream") 1 else null },
        asset = { ByteArrayInputStream("/* $it */".toByteArray()) },
        userAgent = "Telly-test",
    )
    private lateinit var engine: ApplicationEngine
    private var port = 0

    @Before
    fun start() {
        // The channel's address redirects, as these providers do; the playlist names its segments beside itself.
        provider.pages["/live/alice/s3cret/41.m3u8"] = FakeProvider.Page("302 Found", ByteArray(0), listOf("Location: /hls/alice/s3cret/41/index.m3u8"))
        provider.pages["/hls/alice/s3cret/41/index.m3u8"] = FakeProvider.Page(
            "200 OK",
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:2200\n#EXTINF:10.0,\nseg2200.ts\n#EXTINF:10.0,\nseg2201.ts\n".toByteArray(),
            listOf("Content-Type: application/x-mpegURL"),
        )
        provider.pages["/hls/alice/s3cret/41/seg2200.ts"] = FakeProvider.Page("200 OK", segment, listOf("Content-Type: video/mp2t"))
        engine = embeddedServer(CIO, port = 0, host = "127.0.0.1") {
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true; encodeDefaults = true }) }
            routing { with(routes) { install() } }
        }.start(wait = false)
        port = runBlocking { engine.resolvedConnectors().first().port }
    }

    @After
    fun stop() {
        engine.stop(100, 500)
        provider.close()
    }

    private fun ask(method: String, path: String, body: String? = null): Answer {
        val c = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 5_000
        c.readTimeout = 10_000
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray()) }
        }
        val status = c.responseCode
        val bytes = (if (status < 400) c.inputStream else c.errorStream)?.use { it.readBytes() } ?: ByteArray(0)
        return Answer(status, c.contentType, bytes).also { c.disconnect() }
    }

    private fun preview(id: String) = ask("POST", "/api/preview", """{"channelId":"$id"}""")
    private fun started(a: Answer) = json.decodeFromString(PreviewRoutes.Started.serializer(), a.text)
    private fun problem(a: Answer) = json.decodeFromString(PreviewRoutes.Problem.serializer(), a.text)
    private fun state(token: String) = json.decodeFromString(PreviewRoutes.State.serializer(), ask("GET", "/api/preview/$token").text)

    /** Nothing the page receives may say where the stream comes from. */
    private fun assertNoAddress(text: String) {
        assertFalse(text, text.contains("alice"))
        assertFalse(text, text.contains("s3cret"))
        assertFalse(text, text.contains(provider.port.toString()))
    }

    @Test
    fun `a live channel plays through the TV and the page never sees its address`() {
        val begun = preview("ch41")
        assertEquals(200, begun.status)
        assertNoAddress(begun.text)
        val s = started(begun)
        assertEquals("hls", s.kind)
        assertEquals(180, s.seconds)
        assertTrue(s.url!!, Regex("/preview/[0-9a-f]{32}/index\\.m3u8").matches(s.url!!))

        val playlist = ask("GET", s.url!!)
        assertEquals(200, playlist.status)
        assertTrue(playlist.type!!, playlist.type!!.startsWith("application/vnd.apple.mpegurl"))
        assertEquals("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:2200\n#EXTINF:10.0,\n0.ts\n#EXTINF:10.0,\n1.ts\n", playlist.text)
        assertNoAddress(playlist.text)

        val video = ask("GET", s.url!!.replace("index.m3u8", "0.ts"))
        assertEquals(200, video.status)
        assertTrue(video.type!!, video.type!!.startsWith("video/mp2t"))
        assertArrayEquals(segment, video.body)

        // The segment was asked for beside the playlist's real place, after the redirect, as the channel's own player would.
        assertEquals(
            listOf("/live/alice/s3cret/41.m3u8", "/hls/alice/s3cret/41/index.m3u8", "/hls/alice/s3cret/41/seg2200.ts"),
            provider.asked.toList(),
        )
        assertEquals(setOf("UA-test"), provider.agents.toSet())
    }

    @Test
    fun `the TV always wins`() {
        // The TV is on another channel of the one-stream subscription: no preview, and the provider is not asked.
        onTv = listOf(other)
        val refused = preview("ch41")
        assertEquals(409, refused.status)
        assertEquals("tv", problem(refused).code)
        assertNoAddress(refused.text)
        onTv = listOf(live)
        assertEquals(409, preview("ch41").status)
        onTv = listOf(films)
        assertEquals(409, preview("ch41").status)
        assertTrue(provider.asked.isEmpty())

        // The TV is off: the preview plays, until the TV tunes a channel of that subscription.
        onTv = emptyList()
        val s = started(preview("ch41"))
        val token = s.url!!.split('/')[2]
        assertEquals(200, ask("GET", s.url!!).status)
        assertTrue(state(token).active)
        routes.tvChanged()
        assertTrue(state(token).active)
        onTv = listOf(other)
        routes.tvChanged()
        assertEquals(410, ask("GET", s.url!!).status)
        assertEquals(410, ask("GET", s.url!!.replace("index.m3u8", "0.ts")).status)
        val after = state(token)
        assertFalse(after.active)
        assertEquals("tv_needs_it", after.ended)
    }

    @Test
    fun `closing the preview lets go of the stream`() {
        val s = started(preview("ch41"))
        val token = s.url!!.split('/')[2]
        assertEquals(200, ask("GET", s.url!!).status)
        assertEquals(200, ask("DELETE", "/api/preview/$token").status)
        val asked = provider.asked.size
        assertEquals(410, ask("GET", s.url!!).status)
        assertEquals(asked, provider.asked.size)
        assertEquals("closed", state(token).ended)
    }

    @Test
    fun `closing an old preview late does not end the one that replaced it`() {
        val first = started(preview("ch41"))
        provider.pages["/live/alice/s3cret/42.m3u8"] = FakeProvider.Page("200 OK", "#EXTM3U\n#EXTINF:10,\na.ts\n".toByteArray())
        val second = started(preview("ch42"))
        assertEquals(200, ask("DELETE", "/api/preview/${first.url!!.split('/')[2]}").status)
        assertEquals(200, ask("GET", second.url!!).status)
        assertTrue(state(second.url!!.split('/')[2]).active)
    }

    @Test
    fun `a new preview ends the one before`() {
        val first = started(preview("ch41"))
        provider.pages["/live/alice/s3cret/42.m3u8"] = FakeProvider.Page("200 OK", "#EXTM3U\n#EXTINF:10,\na.ts\n".toByteArray())
        val second = started(preview("ch42"))
        assertEquals(410, ask("GET", first.url!!).status)
        assertEquals("#EXTM3U\n#EXTINF:10,\n0.ts\n", ask("GET", second.url!!).text)
    }

    @Test
    fun `only names the preview handed out lead anywhere`() {
        val s = started(preview("ch41"))
        ask("GET", s.url!!)
        assertEquals(410, ask("GET", s.url!!.replace("index.m3u8", "7.ts")).status)
        assertEquals(410, ask("GET", "/preview/${"0".repeat(32)}/index.m3u8").status)
        assertEquals(410, ask("GET", s.url!!.replace("index.m3u8", "..%2F..%2Fchannels.json")).status)
    }

    @Test
    fun `a provider that refuses gives a plain error with no address in it`() {
        provider.pages["/hls/alice/s3cret/41/index.m3u8"] = FakeProvider.Page("403 Forbidden", "user alice is over the limit at /hls/alice/s3cret".toByteArray())
        val s = started(preview("ch41"))
        val answer = ask("GET", s.url!!)
        assertEquals(502, answer.status)
        assertNoAddress(answer.text)
        // A segment that has gone from the provider is the same.
        provider.pages["/hls/alice/s3cret/41/index.m3u8"] = FakeProvider.Page("200 OK", "#EXTM3U\n#EXTINF:10,\ngone.ts\n".toByteArray())
        ask("GET", s.url!!)
        val missing = ask("GET", s.url!!.replace("index.m3u8", "0.ts"))
        assertEquals(502, missing.status)
        assertNoAddress(missing.text)
    }

    @Test
    fun `a YouTube channel gets YouTube's own player and no relay`() {
        val s = started(preview("yt"))
        assertEquals("youtube", s.kind)
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&rel=0&playsinline=1", s.embed)
        assertEquals(null, s.url)
        assertEquals(null, routes.sessions.active())
    }

    @Test
    fun `film channels and channels that are gone are refused in plain words`() {
        val film = preview("f")
        assertEquals(409, film.status)
        assertTrue(problem(film).error.contains("Play on TV"))
        assertEquals(404, preview("nope").status)
    }

    @Test
    fun `the player library is served from the TV itself`() {
        val js = ask("GET", "/hls.min.js")
        assertEquals(200, js.status)
        assertTrue(js.type!!, js.type!!.startsWith("text/javascript"))
        assertEquals("/* config/hls.min.js */", js.text)
    }
}
