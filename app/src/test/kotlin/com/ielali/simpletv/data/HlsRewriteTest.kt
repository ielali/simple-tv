package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HlsRewriteTest {

    private val base = "http://tv.example.com:8080/live/alice/s3cret/41.m3u8"

    /** Names the way the relay does: a number per address, the same address always the same number. */
    private class Names {
        val seen = LinkedHashMap<String, String>()
        val name: (String) -> String = { address -> seen.getOrPut(address) { "n${seen.size}" } }
    }

    @Test
    fun `segments next to the playlist get made-up names and the page sees no address`() {
        val names = Names()
        val out = HlsRewrite.rewrite(
            "#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:2200\n#EXTINF:10.0,\n41_2200.ts\n#EXTINF:10.0,\n41_2201.ts\n",
            base, names.name,
        )
        assertEquals("#EXTM3U\n#EXT-X-TARGETDURATION:10\n#EXT-X-MEDIA-SEQUENCE:2200\n#EXTINF:10.0,\nn0\n#EXTINF:10.0,\nn1\n", out.trimEnd() + "\n")
        assertEquals(
            listOf("http://tv.example.com:8080/live/alice/s3cret/41_2200.ts", "http://tv.example.com:8080/live/alice/s3cret/41_2201.ts"),
            names.seen.keys.toList(),
        )
        assertFalse(out.contains("alice"))
        assertFalse(out.contains("s3cret"))
        assertFalse(out.contains("example.com"))
    }

    @Test
    fun `whole addresses, addresses from the server's root and query strings are all swapped`() {
        val names = Names()
        val out = HlsRewrite.rewrite(
            "#EXTM3U\n#EXTINF:10,\nhttp://cdn.example.net/hls/alice/s3cret/7.ts?token=abc\n#EXTINF:10,\n/hls/8.ts\n#EXTINF:10,\n../9.ts\n",
            base, names.name,
        )
        assertEquals(
            listOf(
                "http://cdn.example.net/hls/alice/s3cret/7.ts?token=abc",
                "http://tv.example.com:8080/hls/8.ts",
                "http://tv.example.com:8080/live/alice/9.ts",
            ),
            names.seen.keys.toList(),
        )
        assertFalse(out.contains("://"))
        assertFalse(out.contains("token"))
    }

    @Test
    fun `a list of qualities and its sound, key and start addresses are swapped too`() {
        val names = Names()
        val out = HlsRewrite.rewrite(
            """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Arabic",URI="audio/ar.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=4000000,AUDIO="a"
            hd/index.m3u8
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.org/k?u=alice&p=s3cret",IV=0x1
            #EXT-X-MAP:URI="init.mp4"
            """.trimIndent(),
            base, names.name,
        )
        assertTrue(out.contains("""#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="a",NAME="Arabic",URI="n0""""))
        assertTrue(out.contains("\nn1\n"))
        assertTrue(out.contains("""#EXT-X-KEY:METHOD=AES-128,URI="n2",IV=0x1"""))
        assertTrue(out.contains("""#EXT-X-MAP:URI="n3""""))
        assertEquals("http://tv.example.com:8080/live/alice/s3cret/hd/index.m3u8", names.seen.keys.toList()[1])
        assertFalse(out.contains("s3cret"))
    }

    @Test
    fun `an address anywhere else never reaches the page`() {
        val out = HlsRewrite.rewrite(
            "#EXTM3U\n#EXT-X-SESSION-DATA:DATA-ID=\"x\",VALUE=\"http://tv.example.com/alice/s3cret\"\n#EXTINF:10.0,http://tv.example.com/alice/s3cret/logo.png\n1.ts\n",
            base, Names().name,
        )
        assertEquals("#EXTM3U\n#EXTINF:10.0,\nn0\n", out)
    }

    @Test
    fun `a key written into the playlist is left alone and Windows line ends are handled`() {
        val out = HlsRewrite.rewrite("#EXTM3U\r\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\"\r\n#EXTINF:4,\r\n1.ts\r\n", base, Names().name)
        assertEquals("#EXTM3U\n#EXT-X-KEY:METHOD=AES-128,URI=\"data:text/plain;base64,AAAA\"\n#EXTINF:4,\nn0\n", out)
    }

    @Test
    fun `a playlist is told from video by how it starts`() {
        assertTrue(HlsRewrite.isPlaylist("#EXTM3U\n#EXT-X".toByteArray()))
        assertTrue(HlsRewrite.isPlaylist(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "#EXTM3U".toByteArray()))
        assertFalse(HlsRewrite.isPlaylist(byteArrayOf(0x47, 0x40, 0x00, 0x10, 0x00, 0x00, 0x00, 0x00)))
        assertFalse(HlsRewrite.isPlaylist("#EXT".toByteArray()))
        // Only the bytes that were really read count.
        assertFalse(HlsRewrite.isPlaylist("#EXTM3U".toByteArray(), length = 4))
    }
}
