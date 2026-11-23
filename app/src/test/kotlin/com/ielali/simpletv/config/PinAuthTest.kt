package com.ielali.simpletv.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PinAuthTest {

    private var now = 0L
    private val auth = PinAuth(clock = { now })

    @Test
    fun `right pin gives a session token`() {
        val r = auth.login("1234", "1234") as PinAuth.LoginResult.Ok
        assertTrue(auth.isValid(r.token))
        assertFalse(auth.isValid("nope"))
        assertFalse(auth.isValid(null))
    }

    @Test
    fun `five wrong tries lock for five minutes`() {
        (4 downTo 1).forEach { left ->
            assertEquals(PinAuth.LoginResult.Wrong(left), auth.login("0000", "1234"))
        }
        val locked = auth.login("0000", "1234") as PinAuth.LoginResult.Locked
        assertEquals(300L, locked.retryAfterSeconds)
        now += 60_000
        assertEquals(PinAuth.LoginResult.Locked(240), auth.login("1234", "1234"))
        now += 240_000
        assertTrue(auth.login("1234", "1234") is PinAuth.LoginResult.Ok)
    }

    @Test
    fun `a right pin resets the count`() {
        repeat(3) { auth.login("0000", "1234") }
        auth.login("1234", "1234")
        assertEquals(PinAuth.LoginResult.Wrong(4), auth.login("0000", "1234"))
    }

    @Test
    fun `reset signs everyone out`() {
        val r = auth.login("1234", "1234") as PinAuth.LoginResult.Ok
        auth.reset()
        assertFalse(auth.isValid(r.token))
    }

    @Test
    fun `pins are 4 to 6 digits`() {
        assertTrue(PinAuth.isValidPin("1234"))
        assertTrue(PinAuth.isValidPin("123456"))
        assertFalse(PinAuth.isValidPin("123"))
        assertFalse(PinAuth.isValidPin("12a4"))
        assertEquals(PinAuth.LoginResult.NoPin, auth.login("1234", null))
    }

    @Test
    fun `issued and revoked sessions`() {
        val auth = PinAuth(clock = { 0L })
        val t = auth.issue()
        assertTrue(auth.isValid(t))
        auth.revoke(t)
        assertFalse(auth.isValid(t))
    }
}
