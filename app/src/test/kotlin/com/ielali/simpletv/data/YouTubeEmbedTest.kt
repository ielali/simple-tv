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
    fun `watch url targets the youtube app`() {
        assertEquals("https://www.youtube.com/watch?v=dQw4w9WgXcQ", YouTubeEmbed.watchUrl(Target.Video("dQw4w9WgXcQ")))
        assertEquals(
            "https://www.youtube.com/channel/UCupvZG-5ko_eiXAupbDfxWw/live",
            YouTubeEmbed.watchUrl(Target.ChannelLive("UCupvZG-5ko_eiXAupbDfxWw")),
        )
    }
}
