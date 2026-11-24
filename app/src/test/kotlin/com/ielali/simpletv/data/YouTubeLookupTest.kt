package com.ielali.simpletv.data

import com.ielali.simpletv.youtube.YouTubeEmbed
import com.ielali.simpletv.youtube.YouTubeLookup
import com.ielali.simpletv.youtube.YouTubeLookup.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeLookupTest {

    private val seen = mutableListOf<Pair<String, Map<String, String>>>()
    private var keyOk = true
    private var quota = false

    private val youtube = TextFetcher { url, headers ->
        seen += url to headers
        when {
            quota && url.contains("googleapis") -> HttpResult(403, """{"error":{"errors":[{"reason":"quotaExceeded"}]}}""")
            !keyOk && url.contains("googleapis") -> HttpResult(400, """{"error":{"errors":[{"reason":"keyInvalid"}],"message":"API key not valid"}}""")
            url.contains("oembed") -> HttpResult(200, """{"title":"Morning news","author_name":"Sky News","thumbnail_url":"http://i/t.jpg"}""")
            url.contains("/videos?") -> HttpResult(200, """{"items":[{"snippet":{"title":"Morning news","channelTitle":"Sky News","channelId":"UCoMdktPbSTixAyNGwb-UYkQ","liveBroadcastContent":"live"}}]}""")
            url.contains("forHandle=%40skynews") -> HttpResult(200, """{"items":[{"id":"UCoMdktPbSTixAyNGwb-UYkQ"}]}""")
            url.contains("forHandle=") -> HttpResult(200, """{"items":[]}""")
            url.contains("/channels?") -> HttpResult(200, """{"items":[{"snippet":{"title":"Sky News","thumbnails":{"high":{"url":"http://i/c.jpg"}}}}]}""")
            url.contains("/search?") -> HttpResult(200, """{"items":[{"id":{"videoId":"x"}}]}""")
            url.contains("/playlists?") -> HttpResult(200, """{"items":[{"snippet":{"title":"Old songs","channelTitle":"Fairuz","thumbnails":{"high":{"url":"http://i/p.jpg"}}},"contentDetails":{"itemCount":37}}]}""")
            else -> HttpResult(404, "")
        }
    }
    private val lookup = YouTubeLookup(youtube)
    private val channelId = "UCoMdktPbSTixAyNGwb-UYkQ"

    @Test
    fun `video links preview without a key`() {
        val p = (lookup.preview("https://youtu.be/dQw4w9WgXcQ", null) as Result.Found).preview
        assertEquals("Morning news", p.title)
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", p.source)
        assertNull(p.live)
        assertTrue(seen.none { it.first.contains("googleapis") })
    }

    @Test
    fun `channel ids work without a key and get name and live state with one`() {
        val bare = (lookup.preview(channelId, null) as Result.Found).preview
        assertEquals(channelId to null, bare.source to bare.title)
        val full = (lookup.preview(channelId, "k") as Result.Found).preview
        assertEquals("Sky News", full.title)
        assertEquals(true, full.live)
        assertEquals("http://i/c.jpg", full.thumbnail)
    }

    @Test
    fun `handles need a key, then resolve to the channel`() {
        assertEquals(YouTubeLookup.NEEDS_KEY, (lookup.preview("@skynews", null) as Result.Problem).code)
        val p = (lookup.preview("https://www.youtube.com/@skynews", "k") as Result.Found).preview
        assertEquals(channelId, p.source)
        assertEquals(YouTubeLookup.NOT_FOUND, (lookup.preview("@nobodyhere", "k") as Result.Problem).code)
    }

    @Test
    fun `the key travels in a header, never in the address`() {
        lookup.preview(channelId, "secret-key")
        val api = seen.filter { it.first.contains("googleapis") }
        assertTrue(api.isNotEmpty())
        assertTrue(api.all { it.second["X-Goog-Api-Key"] == "secret-key" && !it.first.contains("secret-key") })
    }

    @Test
    fun `key and quota problems are explained`() {
        keyOk = false
        assertEquals(YouTubeLookup.KEY_REJECTED, (lookup.preview(channelId, "bad") as Result.Problem).code)
        keyOk = true; quota = true
        val r = lookup.preview(channelId, "k") as Result.Problem
        assertEquals(YouTubeLookup.QUOTA, r.code)
        assertFalse(r.message.contains("http"))
    }

    @Test
    fun `garbage is rejected kindly`() {
        assertEquals(YouTubeLookup.INVALID, (lookup.preview("hello world", null) as Result.Problem).code)
        assertEquals(YouTubeLookup.INVALID, (lookup.preview(" ", null) as Result.Problem).code)
    }

    @Test
    fun `playlist links preview with their name, and the number of videos with a key`() {
        val link = "https://www.youtube.com/watch?v=PbL-BAeRDMQ&list=PLVVfonyXVJI3lLAOb1_wDrgBonbhI6gEK"
        val bare = (lookup.preview(link, null) as Result.Found).preview
        assertEquals("playlist", bare.kind)
        assertEquals(link, bare.source)
        assertEquals("PbL-BAeRDMQ", bare.startVideo)
        assertEquals("Morning news", bare.title) // from oEmbed in this fake
        assertNull(bare.count)
        assertTrue(seen.none { it.first.contains("googleapis") })
        val keyed = (lookup.preview(link, "k") as Result.Found).preview
        assertEquals(37, keyed.count)
        assertTrue(seen.filter { it.first.contains("googleapis") }.all { !it.first.contains("key=") })
    }

    @Test
    fun `channel pictures for live channels, videos and playlists, batched`() {
        val calls = mutableListOf<String>()
        val api = TextFetcher { url, headers ->
            calls += url
            assertEquals("Bearer t", headers["Authorization"])
            when {
                url.contains("/videos?") -> HttpResult(200, """{"items":[{"id":"v1","snippet":{"channelId":"UCb"}}]}""")
                url.contains("/playlists?") -> HttpResult(200, """{"items":[{"id":"PL1","snippet":{"channelId":"UCa"}}]}""")
                url.contains("/channels?") -> HttpResult(200, """{"items":[{"id":"UCa","snippet":{"thumbnails":{"default":{"url":"http://i/a88.jpg"},"high":{"url":"http://i/a800.jpg"}}}},{"id":"UCb","snippet":{"thumbnails":{"medium":{"url":"http://i/b.jpg"}}}}]}""")
                else -> HttpResult(404, "")
            }
        }
        val pics = YouTubeLookup(api).pictures(
            mapOf(
                "live" to YouTubeEmbed.Target.ChannelLive("UCa"),
                "vid" to YouTubeEmbed.Target.Video("v1"),
                "list" to YouTubeEmbed.Target.Playlist("PL1"),
                "gone" to YouTubeEmbed.Target.ChannelLive("UCgone"),
            ),
            mapOf("Authorization" to "Bearer t"),
        )
        assertEquals(mapOf("live" to "http://i/a800.jpg", "vid" to "http://i/b.jpg", "list" to "http://i/a800.jpg"), pics)
        assertEquals("one call per kind", 3, calls.size)
        assertTrue(calls.last().contains("id=UCa,UCgone,UCb") || calls.last().contains("UCa") && calls.last().contains("UCb"))
    }
}
