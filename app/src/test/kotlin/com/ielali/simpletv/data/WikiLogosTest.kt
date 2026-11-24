package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WikiLogosTest {

    @Test
    fun `names are tried whole, then as their brand, never as a generic word`() {
        assertEquals(listOf("Netflix Documentary", "Netflix"), WikiLogos.queries("UK: Netflix Documentary HD"))
        assertEquals(listOf("Sky News Arabia", "Sky News", "Sky"), WikiLogos.queries("|AR| Sky News Arabia (backup)"))
        assertEquals("only generic words: nothing to look up", emptyList<String>(), WikiLogos.queries("Action Movies"))
        assertEquals(emptyList<String>(), WikiLogos.queries("Movies"))
        assertEquals(listOf("MBC 2", "MBC"), WikiLogos.queries("MBC 2 FHD"))
        assertEquals("ar", WikiLogos.language("الجزيرة"))
        assertEquals("en", WikiLogos.language("Netflix"))
    }

    private val search = """{"search":[
      {"id":"Q1","label":"Netflix Documentary Films","description":"film studio","match":{"type":"label","text":"Netflix Documentary Films"}},
      {"id":"Q907311","label":"Netflix","description":"American subscription video on-demand over-the-top streaming service","match":{"type":"label","text":"Netflix"}},
      {"id":"Q2","label":"Netflix","description":"family name","match":{"type":"label","text":"Netflix"}},
      {"id":"Q3","label":"Netflix TV","description":"television channel","match":{"type":"alias","text":"Netflix Television"}}
    ]}"""

    @Test
    fun `only an exact name described as TV counts`() {
        assertEquals(listOf("Q907311"), WikiLogos.pick(search, "Netflix"))
        assertEquals(emptyList<String>(), WikiLogos.pick(search, "Netflix Documentary"))
        assertEquals(listOf("Q907311", "Q3"), WikiLogos.choices(search).map { it.first })
    }

    @Test
    fun `the logo file skips deprecated claims and prefers the preferred one`() {
        val claims = """{"claims":{"P154":[
          {"rank":"deprecated","mainsnak":{"datavalue":{"value":"Old logo.svg"}}},
          {"rank":"normal","mainsnak":{"datavalue":{"value":"Netflix 2014 logo.svg"}}},
          {"rank":"preferred","mainsnak":{"datavalue":{"value":"Netflix 2015 logo.svg"}}}
        ]}}"""
        assertEquals("Netflix 2015 logo.svg", WikiLogos.logoFile(claims))
        assertNull(WikiLogos.logoFile("""{"claims":{}}"""))
        assertNull(WikiLogos.logoFile("not json"))
    }

    @Test
    fun `commons addresses, drawings as PNG`() {
        assertEquals(
            "https://upload.wikimedia.org/wikipedia/commons/thumb/0/08/Netflix_2015_logo.svg/330px-Netflix_2015_logo.svg.png",
            WikiLogos.imageUrl("Netflix 2015 logo.svg"),
        )
        assertEquals("https://upload.wikimedia.org/wikipedia/commons/8/80/Aljazeera_eng.png", WikiLogos.imageUrl("Aljazeera eng.png"))
        assertTrue(WikiLogos.searchUrl("الجزيرة").contains("language=ar"))
    }
}
