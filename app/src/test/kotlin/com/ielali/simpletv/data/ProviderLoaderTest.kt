package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.URI

/**
 * Runs ProviderLoader against an in-memory fake Xtream panel. (Android unit tests compile against
 * android.jar, which has no com.sun.net.httpserver; HttpFetcherTest covers the real HTTP class.)
 */
class ProviderLoaderTest {

    private val requests = mutableListOf<Pair<String, String?>>() // path?query to User-Agent

    // Panel behaviour, changed per test
    private var accountBody = """{"user_info":{"auth":1,"status":"Active","exp_date":"4102444800","max_connections":"1","active_cons":"0","allowed_output_formats":["m3u8","ts"]}}"""
    private var apiEnabled = true
    private var playlistEnabled = true
    private var refuseUserAgent: String? = null

    private val streams = """[
        {"num":2,"name":"Sport 1","stream_id":202,"stream_icon":"http://logo/s1.png","category_id":"7"},
        {"num":1,"name":"News 24","stream_id":"101","stream_icon":"","category_id":5},
        {"num":3,"name":"Mystery","stream_id":303,"category_id":null}
    ]"""
    private val categories = """[{"category_id":"5","category_name":"UK | News"},{"category_id":7,"category_name":"UK | Sport"}]"""
    private val playlist = "#EXTM3U\n#EXTINF:-1 group-title=\"Films\",Film 1\nhttp://panel/live/u/p/1.ts\n"

    private val base = "http://panel.test:8080"

    /** Routes like the real panel; "unreachable" host throws like a refused connection. */
    private val panel = TextFetcher { url, headers ->
        val uri = URI(url)
        if (uri.host == "unreachable.test") throw ConnectException("Connection refused")
        val target = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")
        val ua = headers["User-Agent"]
        requests += target to ua
        val (code, body) = when {
            refuseUserAgent != null && ua == refuseUserAgent -> 403 to "blocked"
            target.startsWith("/player_api.php") && !apiEnabled -> 404 to "no api"
            target.contains("action=get_live_streams") -> 200 to streams
            target.contains("action=get_live_categories") -> 200 to categories
            target.startsWith("/player_api.php") -> 200 to accountBody
            target.startsWith("/get.php") && playlistEnabled -> 200 to playlist
            target.startsWith("/list.m3u") -> 200 to playlist
            else -> 404 to "not found"
        }
        HttpResult(code, body)
    }

    private fun xtream(password: String = "s3cret!") =
        Provider(id = "p", name = "P", kind = ProviderKind.XTREAM, url = base, username = "user", password = password)

    private fun load(p: Provider) = ProviderLoader(panel).load(p)

    @Test
    fun `xtream api import names, groups and orders channels`() {
        val r = load(xtream()) as ProviderLoader.Result.Loaded
        assertEquals("Xtream API", r.source)
        assertEquals(listOf("News 24", "Sport 1", "Mystery"), r.channels.map { it.name })
        assertEquals(listOf("UK | News", "UK | Sport", null), r.channels.map { it.group })
        assertTrue(r.channels[0].url.endsWith("/live/user/s3cret%21/101.m3u8"))
        assertEquals("http://logo/s1.png", r.channels[1].logoUrl)
        assertEquals(null, r.channels[0].logoUrl)
        assertEquals(4102444800L, r.account?.expiresAt)
        assertTrue(r.channels.all { it.headers.isEmpty() }) // default User-Agent was accepted
    }

    @Test
    fun `ts is used when the account does not allow hls`() {
        accountBody = """{"user_info":{"auth":"1","status":"Active","allowed_output_formats":["ts"]}}"""
        val r = load(xtream()) as ProviderLoader.Result.Loaded
        assertTrue(r.channels[0].url.endsWith(".ts"))
    }

    @Test
    fun `wrong login is explained without trying further`() {
        accountBody = """{"user_info":{"auth":0}}"""
        val r = load(xtream()) as ProviderLoader.Result.Failed
        assertTrue(r.message, r.message.contains("did not accept this username and password"))
        assertFalse(requests.any { it.first.startsWith("/get.php") })
    }

    @Test
    fun `expired or disabled accounts are explained`() {
        accountBody = """{"user_info":{"auth":1,"status":"Active","exp_date":"1000"}}"""
        assertTrue((load(xtream()) as ProviderLoader.Result.Failed).message.contains("expired"))
        accountBody = """{"user_info":{"auth":1,"status":"Banned"}}"""
        assertTrue((load(xtream()) as ProviderLoader.Result.Failed).message.contains("Banned"))
    }

    @Test
    fun `falls back to the playlist when the api is disabled`() {
        apiEnabled = false
        val r = load(xtream()) as ProviderLoader.Result.Loaded
        assertEquals("M3U playlist", r.source)
        assertEquals(listOf("Film 1"), r.channels.map { it.name })
    }

    @Test
    fun `retries with a player user agent and keeps it for playback`() {
        refuseUserAgent = ProviderLoader.DEFAULT_USER_AGENT
        val r = load(xtream()) as ProviderLoader.Result.Loaded
        assertEquals("Xtream API", r.source)
        assertTrue(r.channels.all { it.headers["User-Agent"] == ProviderLoader.PLAYER_USER_AGENT })
    }

    @Test
    fun `nothing available gives a clear message with no credentials in it`() {
        apiEnabled = false; playlistEnabled = false
        val r = load(xtream()) as ProviderLoader.Result.Failed
        assertTrue(r.message, r.message.contains("HTTP 404"))
        assertFalse(r.message.contains("s3cret"))
        assertFalse(r.message.contains("user"))
    }

    @Test
    fun `unreachable server is reported as such`() {
        val closed = Provider(id = "p", name = "P", url = "http://unreachable.test:8080", username = "user", password = "pw")
        val r = load(closed) as ProviderLoader.Result.Failed
        assertTrue(r.message, r.message.contains("could not be reached"))
    }

    @Test
    fun `m3u provider link works`() {
        val m3u = Provider(id = "m", name = "M", kind = ProviderKind.M3U, url = "$base/list.m3u")
        val r = load(m3u) as ProviderLoader.Result.Loaded
        assertEquals(1, r.channels.size)
    }

    @Test
    fun `groups are counted largest first with uncategorised last among equals`() {
        val chs = listOf("A", "B", "B", null).mapIndexed { i, g -> Channel(id = "$i", number = 0, name = "$i", url = "u", group = g) }
        assertEquals(listOf("B" to 2, "" to 1, "A" to 1), ProviderLoader.groups(chs))
    }
}
