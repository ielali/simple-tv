package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class M3uParserTest {

    @Test
    fun `parses extinf attributes and url`() {
        val text = """
            #EXTM3U
            #EXTINF:-1 tvg-id="rte1.ie" tvg-logo="https://logo/rte1.png" group-title="Ireland",RTÉ One
            #EXTVLCOPT:http-user-agent=Mozilla/5.0
            https://example.com/rte1/index.m3u8
            #EXTINF:-1,Plain Channel
            https://example.com/plain.ts
        """.trimIndent()

        val channels = M3uParser.parse(text)
        assertEquals(2, channels.size)
        with(channels[0]) {
            assertEquals("RTÉ One", name)
            assertEquals("https://logo/rte1.png", logoUrl)
            assertEquals("Ireland", group)
            assertEquals("https://example.com/rte1/index.m3u8", url)
            assertEquals(mapOf("User-Agent" to "Mozilla/5.0"), headers)
            assertEquals(SourceType.STREAM, type)
            assertEquals(0, number)
        }
        with(channels[1]) {
            assertEquals("Plain Channel", name)
            assertNull(logoUrl)
            assertEquals(emptyMap<String, String>(), headers)
        }
    }

    @Test
    fun `youtube urls become youtube channels`() {
        val channels = M3uParser.parse("#EXTINF:-1,News Live\nhttps://www.youtube.com/watch?v=abcdefghijk")
        assertEquals(SourceType.YOUTUBE, channels.single().type)
    }

    @Test
    fun `url without extinf gets a name from the path`() {
        val channels = M3uParser.parse("https://example.com/streams/sport.m3u8?token=1")
        assertEquals("sport.m3u8", channels.single().name)
    }
}
