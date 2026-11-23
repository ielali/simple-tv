package com.ielali.simpletv.guard

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** The accessibility XML filters events by package; it must watch exactly what the policy acts on. */
class HomeGuardConfigTest {

    @Test
    fun `accessibility xml watches the policy packages`() {
        val xml = File("src/main/res/xml/home_guard_service.xml").readText()
        val listed = Regex("""android:packageNames="([^"]*)"""").find(xml)!!.groupValues[1].split(',').map { it.trim() }.toSet()
        assertEquals(HomeGuardPolicy.WATCHED_PACKAGES, listed)
    }
}
