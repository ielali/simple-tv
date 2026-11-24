package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TextCleanTest {

    @Test
    fun `entities in names are decoded, also when encoded twice`() {
        assertEquals("Tom & Jerry", TextClean.name("Tom &amp; Jerry"))
        assertEquals("Tom & Jerry", TextClean.name("Tom &amp;amp; Jerry"))
        assertEquals("Rock 'n' Roll \"Live\"", TextClean.name("Rock &#39;n&#039; Roll &quot;Live&quot;"))
        assertEquals("Café", TextClean.name("Caf&#xE9;"))
        assertEquals("A < B > C", TextClean.name("A &lt; B &gt; C"))
    }

    @Test
    fun `unknown entities and plain ampersands stay as they are`() {
        assertEquals("AT&T", TextClean.name("AT&T"))
        assertEquals("Fish & Chips", TextClean.name("Fish & Chips"))
        assertEquals("&bogus; here", TextClean.name("&bogus; here"))
    }

    @Test
    fun `spaces are tidied and arabic is untouched`() {
        assertEquals("MBC 1 HD إم بي سي", TextClean.name("  MBC 1 HD \t إم بي سي \n"))
    }

    @Test
    fun `guide titles that are only a path or a link are dropped`() {
        assertNull(TextClean.guideTitle("/home/1/1/2024"))
        assertNull(TextClean.guideTitle("home/1/1"))
        assertNull(TextClean.guideTitle("http://example.com/epg/1"))
        assertNull(TextClean.guideTitle("   "))
        assertNull(TextClean.guideTitle(null))
        assertEquals("News at 7", TextClean.guideTitle("News at 7"))
        assertEquals("24/7 Comedy", TextClean.guideTitle("24/7 Comedy"))
        assertEquals("Tom & Jerry", TextClean.guideTitle("Tom &amp; Jerry"))
    }
}
