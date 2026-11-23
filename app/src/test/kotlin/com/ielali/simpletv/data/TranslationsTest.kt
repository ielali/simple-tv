package com.ielali.simpletv.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Every supported language must translate every on-screen string, or Arabic viewers would see English. */
class TranslationsTest {

    private val entry = Regex("""<string name="([^"]+)"([^>]*)>([^<]*)</string>""")

    private fun strings(dir: String): Map<String, Pair<String, Boolean>> =
        entry.findAll(File("src/main/res/$dir/strings.xml").readText()).associate {
            it.groupValues[1] to (it.groupValues[3] to !it.groupValues[2].contains("""translatable="false""""))
        }

    @Test
    fun `every translatable string has a translation for each supported language`() {
        val base = strings("values").filterValues { it.second }.keys
        TvLanguage.SUPPORTED.keys.filter { it != "en" }.forEach { lang ->
            val translated = strings("values-$lang")
            assertEquals("missing in values-$lang", emptySet<String>(), base - translated.keys)
            assertEquals("extra in values-$lang", emptySet<String>(), translated.keys - base)
            translated.forEach { (name, v) -> assertTrue("$lang/$name is empty", v.first.isNotBlank()) }
        }
    }

    @Test
    fun `arabic strings are actually arabic`() {
        strings("values-ar").forEach { (name, v) ->
            assertTrue("ar/$name has no Arabic letters", v.first.any { it in '؀'..'ۿ' })
        }
    }

    @Test
    fun `language tags are validated`() {
        assertEquals("ar", TvLanguage.normalise(" AR "))
        assertEquals("en", TvLanguage.normalise("en"))
        assertEquals(TvLanguage.SYSTEM, TvLanguage.normalise("xx"))
        assertEquals(TvLanguage.SYSTEM, TvLanguage.normalise(null))
        assertTrue(TvLanguage.isRightToLeft("ar"))
    }
}
