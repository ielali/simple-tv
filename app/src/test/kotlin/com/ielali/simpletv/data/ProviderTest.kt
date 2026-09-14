package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderTest {

    @Test
    fun `xtream playlist url from bare host and port`() {
        assertEquals(
            "http://tv.example.com:8080/get.php?username=bob&password=p%40ss%26word&type=m3u_plus&output=ts",
            XtreamCodes.playlistUrl("tv.example.com:8080", "bob", "p@ss&word"),
        )
    }

    @Test
    fun `xtream base url tolerates pasted links and trailing slashes`() {
        assertEquals("http://h:8080", XtreamCodes.baseUrl("http://h:8080/"))
        assertEquals("https://h", XtreamCodes.baseUrl("https://h/get.php?username=a&password=b"))
        assertEquals("http://h:25461", XtreamCodes.baseUrl("h:25461/player_api.php"))
    }

    @Test
    fun `m3u provider with credentials sends basic auth, xtream does not`() {
        val m3u = Provider(id = "1", name = "P", kind = ProviderKind.M3U, url = "https://x/list.m3u", username = "u", password = "p")
        assertEquals(mapOf("Authorization" to "Basic dTpw"), m3u.authHeaders())
        assertEquals("https://x/list.m3u", m3u.playlistUrl())

        val xt = Provider(id = "2", name = "X", kind = ProviderKind.XTREAM, url = "h:80", username = "u", password = "p")
        assertTrue(xt.authHeaders().isEmpty())
        assertTrue(xt.playlistUrl().startsWith("http://h:80/get.php?username=u&password=p"))
    }

    @Test
    fun `import adds only new channels and tags them with the provider`() {
        val provider = Provider(id = "prov", name = "P", kind = ProviderKind.M3U, url = "https://x/l.m3u", username = "u", password = "p", logoUrl = "https://x/logo.png")
        val existing = listOf(
            Channel(id = "a", number = 1, name = "Mine", url = "https://mine/1.m3u8"),
            Channel(id = "b", number = 5, name = "Old", url = "https://x/s1.m3u8", providerId = "prov"),
        )
        val parsed = listOf(
            Channel(id = "p1", number = 0, name = "S1", url = "https://x/s1.m3u8"),
            Channel(id = "p2", number = 0, name = "S2", url = "https://x/s2.m3u8", logoUrl = "https://x/s2.png"),
        )

        val r = ProviderImport.merge(existing, parsed, provider)

        assertEquals(1, r.added)
        assertEquals(1, r.skipped)
        assertEquals(3, r.channels.size)
        val s2 = r.channels.last()
        assertEquals(6, s2.number)
        assertEquals("prov", s2.providerId)
        assertEquals("https://x/s2.png", s2.logoUrl)
        assertEquals("Basic dTpw", s2.headers["Authorization"])
        assertEquals(existing, r.channels.take(2))
    }

    @Test
    fun `import falls back to provider icon`() {
        val provider = Provider(id = "prov", name = "P", url = "h", logoUrl = "https://x/logo.png")
        val r = ProviderImport.merge(emptyList(), listOf(Channel(id = "p", number = 0, name = "S", url = "u")), provider)
        assertEquals("https://x/logo.png", r.channels.single().logoUrl)
    }
}
