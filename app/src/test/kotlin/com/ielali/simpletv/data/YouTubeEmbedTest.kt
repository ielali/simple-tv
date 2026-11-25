package com.ielali.simpletv.data

import com.ielali.simpletv.youtube.YouTubeEmbed
import com.ielali.simpletv.youtube.YouTubeEmbed.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeEmbedTest {

    @Test
    fun `parses the common url shapes`() {
        assertEquals(Target.Video("dQw4w9WgXcQ"), YouTubeEmbed.parse("dQw4w9WgXcQ"))
        assertEquals(Target.Video("dQw4w9WgXcQ"), YouTubeEmbed.parse("https://www.youtube.com/watch?v=dQw4w9WgXcQ&t=1"))
        assertEquals(Target.Video("dQw4w9WgXcQ"), YouTubeEmbed.parse("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Target.Video("dQw4w9WgXcQ"), YouTubeEmbed.parse("https://www.youtube.com/live/dQw4w9WgXcQ?feature=share"))
        assertEquals(Target.ChannelLive("UCupvZG-5ko_eiXAupbDfxWw"), YouTubeEmbed.parse("UCupvZG-5ko_eiXAupbDfxWw"))
        assertEquals(Target.ChannelLive("UCupvZG-5ko_eiXAupbDfxWw"), YouTubeEmbed.parse("https://www.youtube.com/channel/UCupvZG-5ko_eiXAupbDfxWw/live"))
    }

    @Test
    fun `rejects garbage`() {
        assertNull(YouTubeEmbed.parse("https://vimeo.com/123"))
        assertNull(YouTubeEmbed.parse("not a url"))
        assertNull(YouTubeEmbed.parse("https://www.youtube.com/@handle"))
    }

    @Test
    fun `embed url is autoplay without controls`() {
        val url = YouTubeEmbed.embedUrl(Target.ChannelLive("UCupvZG-5ko_eiXAupbDfxWw"))
        assertTrue(url.startsWith("https://www.youtube.com/embed/live_stream?channel=UCupvZG-5ko_eiXAupbDfxWw"))
        assertTrue(url.contains("autoplay=1"))
        assertTrue(url.contains("controls=0"))
    }

    @Test
    fun `the page's preview uses YouTube's player with its controls and the page's own origin`() {
        assertEquals("https://www.youtube.com/embed/dQw4w9WgXcQ?autoplay=1&rel=0&playsinline=1", YouTubeEmbed.previewUrl(Target.Video("dQw4w9WgXcQ")))
        assertEquals(
            "https://www.youtube.com/embed/live_stream?channel=UCupvZG-5ko_eiXAupbDfxWw&autoplay=1&rel=0&playsinline=1",
            YouTubeEmbed.previewUrl(Target.ChannelLive("UCupvZG-5ko_eiXAupbDfxWw")),
        )
        val list = YouTubeEmbed.previewUrl(Target.Playlist("PL123"))
        assertEquals("https://www.youtube.com/embed/videoseries?list=PL123&autoplay=1&rel=0&playsinline=1", list)
        assertTrue(listOf("controls=0", "origin=").none { list.contains(it) })
    }

    @Test
    fun `watch url targets the youtube app`() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YouTubeEmbed.watchUrl(Target.Video("dQw4w9WgXcQ")))
        assertEquals(
            "https://www.youtube.com/channel/UCupvZG-5ko_eiXAupbDfxWw/live",
            YouTubeEmbed.watchUrl(Target.ChannelLive("UCupvZG-5ko_eiXAupbDfxWw")),
        )
    }

    @Test
    fun `handles are recognised but not treated as playable`() {
        assertEquals("@skynews", YouTubeEmbed.handleOf("@skynews"))
        assertEquals("@skynews", YouTubeEmbed.handleOf("https://www.youtube.com/@skynews/live"))
        assertNull(YouTubeEmbed.handleOf("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertNull(YouTubeEmbed.parse("@skynews"))
    }

    @Test
    fun `playlist links become playlists, starting at the linked video`() {
        val list = "PLVVfonyXVJI3lLAOb1_wDrgBonbhI6gEK"
        assertEquals(Target.Playlist(list, "PbL-BAeRDMQ"), YouTubeEmbed.parse("https://www.youtube.com/watch?v=PbL-BAeRDMQ&list=$list"))
        assertEquals(Target.Playlist(list), YouTubeEmbed.parse("https://www.youtube.com/playlist?list=$list"))
        assertEquals(Target.Playlist(list, "PbL-BAeRDMQ"), YouTubeEmbed.parse("https://youtu.be/PbL-BAeRDMQ?list=$list"))
        assertEquals(Target.Playlist(list), YouTubeEmbed.parse(list))
        assertEquals(Target.Playlist(list, null, shuffle = true), YouTubeEmbed.parse("https://www.youtube.com/playlist?list=$list&shuffle=1"))
        assertTrue(YouTubeEmbed.isPlaylist("https://m.youtube.com/playlist?list=$list"))
    }

    @Test
    fun `a mix is not a playlist, the video in its link plays`() {
        assertEquals(Target.Video("PbL-BAeRDMQ"), YouTubeEmbed.parse("https://www.youtube.com/watch?v=PbL-BAeRDMQ&list=RDPbL-BAeRDMQ&start_radio=1"))
    }

    @Test
    fun `the stored playlist link round-trips`() {
        val list = "PLVVfonyXVJI3lLAOb1_wDrgBonbhI6gEK"
        for (t in listOf(Target.Playlist(list), Target.Playlist(list, "PbL-BAeRDMQ"), Target.Playlist(list, null, true), Target.Playlist(list, "PbL-BAeRDMQ", true))) {
            assertEquals(t, YouTubeEmbed.parse(YouTubeEmbed.playlistSource(t.listId, t.startVideo, t.shuffle)))
        }
    }

    @Test
    fun `the playlist page resumes where the channel stopped and only writes checked ids`() {
        val t = Target.Playlist("PLVVfonyXVJI3lLAOb1_wDrgBonbhI6gEK", "PbL-BAeRDMQ")
        val fresh = YouTubeEmbed.playlistHtml(t, null)
        assertTrue(fresh.contains("WANT='PbL-BAeRDMQ', INDEX=0, SEC=0"))
        assertTrue(fresh.contains("https://www.youtube.com/iframe_api"))
        val resumed = YouTubeEmbed.playlistHtml(t, YouTubeEmbed.Resume(index = 7, videoId = "dQw4w9WgXcQ", seconds = 95))
        assertTrue(resumed.contains("WANT='dQw4w9WgXcQ', INDEX=7, SEC=95"))
        val odd = YouTubeEmbed.playlistHtml(t, YouTubeEmbed.Resume(index = 2, videoId = "x');alert(1)//", seconds = 3))
        assertTrue("a bad saved id falls back to the link's video", odd.contains("WANT='PbL-BAeRDMQ'"))
    }
}
