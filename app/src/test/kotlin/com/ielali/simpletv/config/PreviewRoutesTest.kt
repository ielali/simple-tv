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
import org.junit.Assert.assertNull
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The preview player's routes and relay end to end (ADR-029): a real config-server engine in front, a
 * provider on a local socket behind (java.net.ServerSocket, as in HttpFetcherTest).
 */
class PreviewRoutesTest {

    /** A provider that answers each path from [pages] and remembers what it was asked. */
    private class FakeProvider {
        /** [endless]: the body is sent again and again until the other end goes, as a live stream is. */
        class Page(val status: String, val body: ByteArray, val headers: List<String> = emptyList(), val endless: Boolean = false)

        val pages = ConcurrentHashMap<String, Page>()
        val asked = CopyOnWriteArrayList<String>()
        val agents = CopyOnWriteArrayList<String>()
        val passes = CopyOnWriteArrayList<String>()

        /** Bytes of endless pages sent so far, and whether the other end has let go of one. */
        val sent = AtomicLong()
        val dropped = CountDownLatch(1)
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
                if (line.startsWith("Authorization:", ignoreCase = true)) passes += line.substringAfter(':').trim()
            }
            asked += path
            val page = pages[path] ?: Page("404 Not Found", "no such stream at $path".toByteArray())
            val out = c.getOutputStream()
            val extra = page.headers.joinToString("") { "$it\r\n" }
            if (page.endless) {
                out.write("HTTP/1.1 ${page.status}\r\n${extra}Connection: close\r\n\r\n".toByteArray())
                try {
                    while (true) {
                        out.write(page.body)
                        out.flush()
                        sent.addAndGet(page.body.size.toLong())
                    }
                } catch (e: IOException) {
                    dropped.countDown()
                }
                return@use
            }
            out.write("HTTP/1.1 ${page.status}\r\n${extra}Content-Length: ${page.body.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(page.body)
            out.flush()
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

    /** A channel in a subscription's own list, not in the lineup. */
    private val listed = Channel(
        id = "catalog:m3u:7", number = 0, name = "Listed", url = "http://127.0.0.1:${provider.port}/list/alice/s3cret/7.m3u8",
        headers = mapOf("Authorization" to "Basic dTpw"), providerId = "m3u", sourceId = "7",
    )

    @Volatile private var onTv: List<Channel> = emptyList()
    @Volatile private var minutes = 3
    private val logged = CopyOnWriteArrayList<String>()
    private val routes = PreviewRoutes(
        channels = { listOf(live, other, youtube, films) },
        catalogChannel = { providerId, itemId -> listed.takeIf { providerId == "m3u" && itemId == "7" } },
        onTv = { onTv },
        streamLimit = { if (it == "xtream") 1 else null },
        minutes = { minutes },
        asset = { ByteArrayInputStream("/* $it */".toByteArray()) },
        userAgent = "Telly-test",
        log = { logged += it },
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
    private fun previewListed(providerId: String, itemId: String) = ask("POST", "/api/preview", """{"providerId":"$providerId","itemId":"$itemId"}""")
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
        assertEquals(180, s.seconds) // the test's setting: 3 minutes
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
    fun `a channel in a subscription's list plays before it is added`() {
        provider.pages["/list/alice/s3cret/7.m3u8"] = FakeProvider.Page("200 OK", "#EXTM3U\n#EXTINF:6,\nhttp://127.0.0.1:${provider.port}/list/alice/s3cret/7/a.ts\n".toByteArray())
        val begun = previewListed("m3u", "7")
        assertEquals(200, begun.status)
        assertNoAddress(begun.text)
        val s = started(begun)
        assertEquals("hls", s.kind)
        val playlist = ask("GET", s.url!!)
        assertEquals("#EXTM3U\n#EXTINF:6,\n0.ts\n", playlist.text)
        // Asked the way the TV would play it once added: with the subscription's own sign-in.
        assertEquals(listOf("Basic dTpw"), provider.passes.toList())

        assertEquals(404, previewListed("m3u", "8").status)
        assertEquals(404, previewListed("gone", "7").status)
        assertEquals(400, ask("POST", "/api/preview", "{}").status)
    }

    @Test
    fun `a channel in a subscription's list waits for the TV like any other`() {
        // The TV is on the lineup's copy of the same channel: another id, the same stream.
        onTv = listOf(listed.copy(id = "lineup-copy", number = 12))
        val refused = previewListed("m3u", "7")
        assertEquals(409, refused.status)
        assertEquals("tv", problem(refused).code)
        assertTrue(provider.asked.isEmpty())
    }

    @Test
    fun `the time chosen counts from the first video, and the page is told how much is left`() {
        minutes = 5
        val s = started(preview("ch41"))
        assertEquals(300, s.seconds)
        val token = s.url!!.split('/')[2]
        ask("GET", s.url!!)
        val waiting = state(token)
        assertTrue(waiting.active)
        assertFalse(waiting.playing)
        assertEquals(200, ask("GET", s.url!!.replace("index.m3u8", "0.ts")).status)
        val playing = state(token)
        assertTrue(playing.playing)
        assertTrue("${playing.secondsLeft}", playing.secondsLeft in 298..300)
    }

    @Test
    fun `with no limit chosen the preview has no end of its own`() {
        minutes = 0
        val s = started(preview("ch41"))
        assertNull(s.seconds)
        val token = s.url!!.split('/')[2]
        ask("GET", s.url!!)
        ask("GET", s.url!!.replace("index.m3u8", "0.ts"))
        val now = state(token)
        assertTrue(now.active)
        assertTrue(now.playing)
        assertNull(now.secondsLeft)
        // The TV still wins.
        onTv = listOf(other)
        routes.tvChanged()
        assertEquals("tv_needs_it", state(token).ended)
    }

    @Test
    fun `a channel that is not HLS is refused in words and let go at once`() {
        // The channel's own address answers with video, not a playlist: no browser player here can show that.
        provider.pages["/live/alice/s3cret/42.m3u8"] = FakeProvider.Page("200 OK", segment, listOf("Content-Type: video/mp2t"), endless = true)
        val s = started(preview("ch42"))
        val token = s.url!!.split('/')[2]
        val answer = ask("GET", s.url!!)
        assertEquals(415, answer.status)
        assertEquals("kind", problem(answer).code)
        assertNoAddress(answer.text)
        assertEquals("unplayable", state(token).ended)
        assertTrue(provider.dropped.await(5, TimeUnit.SECONDS))
        assertEquals(410, ask("GET", s.url!!).status)
    }

    @Test
    fun `a browser that stops reading does not keep the provider from the TV`() {
        provider.pages["/hls/alice/s3cret/41/seg2200.ts"] = FakeProvider.Page("200 OK", segment, listOf("Content-Type: video/mp2t"), endless = true)
        val s = started(preview("ch41"))
        ask("GET", s.url!!)
        // A browser that asks for video and then reads none of it, as a tab does when it is frozen.
        Socket("127.0.0.1", port).use { stuck ->
            stuck.getOutputStream().apply {
                write("GET ${s.url!!.replace("index.m3u8", "0.ts")} HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".toByteArray())
                flush()
            }
            // Everything in between fills up, and the provider's stream stands still, held open.
            var last = -1L
            val deadline = System.currentTimeMillis() + 15_000
            while (true) {
                val now = provider.sent.get()
                if (now > 0 && now == last) break
                assertTrue("the stream never stood still", System.currentTimeMillis() < deadline)
                last = now
                Thread.sleep(500)
            }
            assertEquals(1L, provider.dropped.count)

            // The TV tunes a channel of that subscription. Ending the preview is not enough by itself…
            onTv = listOf(other)
            routes.tvChanged()
            assertFalse(provider.dropped.await(700, TimeUnit.MILLISECONDS))
            // …the sweep that follows cuts the provider's connection, with the browser still not reading.
            routes.sweep()
            assertTrue(provider.dropped.await(5, TimeUnit.SECONDS))
        }
    }

    @Test
    fun `the playback log gets timings and never an address`() {
        val s = started(preview("ch41"))
        ask("GET", s.url!!)
        ask("GET", s.url!!.replace("index.m3u8", "0.ts"))
        onTv = listOf(other)
        routes.tvChanged()
        val lines = logged.toList()
        assertEquals("preview: start, channel 41, 3 min", lines[0])
        assertTrue(lines[1], Regex("preview: playlist in \\d+ ms").matches(lines[1]))
        assertTrue(lines[2], Regex("preview: video 0\\.2 MB, first byte \\d+ ms, all \\d+ ms, [\\d.]+ Mbit/s").matches(lines[2]))
        assertEquals("preview: ended, the TV needs the subscription", lines[3])
        assertEquals(4, lines.size)
        val all = lines.joinToString("\n")
        for (secret in listOf("alice", "s3cret", "127.0.0.1", "News", "hls/")) assertFalse(all, all.contains(secret))
    }

    @Test
    fun `the player library is served from the TV itself`() {
        val js = ask("GET", "/hls.min.js")
        assertEquals(200, js.status)
        assertTrue(js.type!!, js.type!!.startsWith("text/javascript"))
        assertEquals("/* config/hls.min.js */", js.text)
    }
}
