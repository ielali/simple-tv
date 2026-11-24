package com.ielali.simpletv.data

import com.ielali.simpletv.youtube.YouTubeAccount
import com.ielali.simpletv.youtube.YouTubeAccount.Outcome
import com.ielali.simpletv.youtube.YouTubeAccount.Poll
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeAccountTest {

    private val gets = mutableListOf<Pair<String, Map<String, String>>>()
    private val posts = mutableListOf<Pair<String, Map<String, String>>>()
    private var tokenReply = HttpResult(428, """{"error":"authorization_pending"}""")

    private val google = YouTubeAccount(
        TextFetcher { url, headers ->
            gets += url to headers
            when {
                url.contains("/subscriptions") && !url.contains("pageToken") -> HttpResult(200, """{"items":[
                    {"snippet":{"title":"Sky News","resourceId":{"channelId":"UCsky"},"thumbnails":{"default":{"url":"https://i/sky.jpg"}}}}],"nextPageToken":"p2"}""")
                url.contains("/subscriptions") -> HttpResult(200, """{"items":[{"snippet":{"title":"Al Jazeera","resourceId":{"channelId":"UCaj"}}}]}""")
                url.contains("/channels?part=contentDetails") -> HttpResult(200, """{"items":[{"id":"UCsky","contentDetails":{"relatedPlaylists":{"uploads":"UUsky"}}}]}""")
                url.contains("/channels?part=snippet") -> HttpResult(200, """{"items":[{"snippet":{"title":"Margaret"}}]}""")
                url.contains("/playlistItems") -> HttpResult(200, """{"items":[{"contentDetails":{"videoId":"v1"}},{"contentDetails":{"videoId":"v2"}}]}""")
                url.contains("/videos") -> HttpResult(200, """{"items":[
                    {"id":"v1","snippet":{"channelId":"UCsky","title":"Sky News Live","liveBroadcastContent":"live"}},
                    {"id":"v2","snippet":{"channelId":"UCsky","title":"Yesterday","liveBroadcastContent":"none"}}]}""")
                else -> HttpResult(404, "{}")
            }
        },
        FormPoster { url, form, _ ->
            posts += url to form
            when (url) {
                YouTubeAccount.DEVICE_URL -> HttpResult(200, """{"device_code":"dc","user_code":"ABCD-EFGH","verification_url":"https://www.google.com/device","expires_in":1800,"interval":5}""")
                YouTubeAccount.TOKEN_URL -> if (form["grant_type"] == "refresh_token") HttpResult(400, """{"error":"invalid_grant"}""") else tokenReply
                else -> HttpResult(200, "{}")
            }
        },
    )

    @Test
    fun `device sign-in asks for read-only YouTube and waits for the phone`() {
        val dc = (google.start("client") as Outcome.Ok).value
        assertEquals("ABCD-EFGH", dc.userCode)
        assertEquals(YouTubeAccount.SCOPE, posts.single().second["scope"])
        assertEquals(Poll.Pending, google.poll("client", "secret", dc.deviceCode))
        tokenReply = HttpResult(200, """{"access_token":"at","refresh_token":"rt","expires_in":3599}""")
        assertEquals(Poll.Signed("at", "rt", 3599), google.poll("client", "secret", dc.deviceCode))
        tokenReply = HttpResult(400, """{"error":"access_denied"}""")
        assertTrue(google.poll("client", "secret", "dc") is Poll.Stop)
        // Secrets travel in the form body, never in the address.
        assertTrue(posts.none { it.first.contains("secret") })
    }

    @Test
    fun `a revoked refresh token means signed out`() {
        val r = google.refresh("client", "secret", "rt") as Outcome.Problem
        assertTrue(r.signedOut)
    }

    @Test
    fun `subscriptions follow pages and calls carry the token in a header`() {
        val subs = (google.subscriptions("at") as Outcome.Ok).value
        assertEquals(listOf("Sky News", "Al Jazeera"), subs.map { it.title })
        assertEquals("https://i/sky.jpg", subs[0].thumbnail)
        assertTrue(gets.all { it.second["Authorization"] == "Bearer at" && !it.first.contains("at&") })
    }

    @Test
    fun `live now comes from the uploads playlist and the videos' live state`() {
        val ups = (google.uploadsPlaylists("at", listOf("UCsky")) as Outcome.Ok).value
        assertEquals("UUsky", ups["UCsky"])
        val vids = (google.latestVideos("at", "UUsky") as Outcome.Ok).value
        val live = (google.liveNow("at", vids) as Outcome.Ok).value
        assertEquals(listOf("v1"), live.map { it.videoId })
        assertEquals("Sky News Live", live.single().title)
        assertFalse(gets.any { it.first.contains("/search") }) // the expensive search call is never used
        assertEquals("Margaret", (google.accountName("at") as Outcome.Ok).value)
    }
}
