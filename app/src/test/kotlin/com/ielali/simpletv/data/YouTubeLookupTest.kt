package com.ielali.simpletv.data

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
}
