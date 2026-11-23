package com.ielali.simpletv.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyLogTest {

    private fun entry(code: Int) = KeyLogEntry(code.toLong(), code, "KEYCODE_$code", "app", "PASS")

    @Test
    fun `keeps the newest entries first up to capacity`() {
        val log = KeyLog(capacity = 3)
        (1..5).forEach { log.record(entry(it)) }
        assertEquals(listOf(5, 4, 3), log.entries().map { it.keyCode })
    }

    @Test
    fun `clear empties the log`() {
        val log = KeyLog()
        log.record(entry(1))
        log.clear()
        assertTrue(log.entries().isEmpty())
    }
}
