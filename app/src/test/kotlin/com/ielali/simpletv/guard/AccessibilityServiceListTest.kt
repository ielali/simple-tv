package com.ielali.simpletv.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessibilityServiceListTest {

    private val guard = "com.ielali.simpletv/com.ielali.simpletv.guard.HomeGuardService"
    private val guardShort = "com.ielali.simpletv/.guard.HomeGuardService"

    @Test
    fun `adds to empty, null and unset values`() {
        assertEquals(guard, AccessibilityServiceList.add(null, guard))
        assertEquals(guard, AccessibilityServiceList.add("", guard))
        assertEquals(guard, AccessibilityServiceList.add("null", guard))
    }

    @Test
    fun `adds after other services and is idempotent across name forms`() {
        assertEquals("com.other/.Svc:$guard", AccessibilityServiceList.add("com.other/.Svc", guard))
        assertEquals("com.other/.Svc:$guardShort", AccessibilityServiceList.add("com.other/.Svc:$guardShort", guard))
        assertTrue(AccessibilityServiceList.contains(guardShort, guard))
    }

    @Test
    fun `remove keeps every other service`() {
        assertEquals("com.other/.Svc", AccessibilityServiceList.remove("com.other/.Svc:$guardShort", guard))
        assertEquals("", AccessibilityServiceList.remove(guard, guard))
        assertFalse(AccessibilityServiceList.contains("com.other/.Svc", guard))
    }
}
