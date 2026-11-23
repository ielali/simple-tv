package com.ielali.simpletv.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountPickerTest {

    private fun n(label: String, clickable: Boolean = false) = ScreenNode(label, clickable)

    private val englishPicker = listOf(
        n("YouTube"),
        n("Back", clickable = true),
        n("Who's watching?"),
        n("Imad", clickable = true),
        n("Sara", clickable = true),
        n("Add account", clickable = true),
        n("Guest", clickable = true),
    )

    @Test
    fun `chooses the first account on the english picker`() {
        assertEquals(PickerAction.Click(3), AccountPickerDetector.decide(englishPicker, canPressOk = true))
    }

    @Test
    fun `recognises the arabic picker`() {
        val arabic = listOf(n("من يشاهد؟"), n("عماد", clickable = true), n("إضافة حساب", clickable = true))
        assertEquals(PickerAction.Click(1), AccountPickerDetector.decide(arabic, canPressOk = true))
    }

    @Test
    fun `recognises a picker by its add-account and guest buttons alone`() {
        val untitled = listOf(n("Imad", clickable = true), n("Add account", clickable = true), n("Use YouTube without an account", clickable = true))
        assertTrue(AccountPickerDetector.isPicker(untitled))
        assertEquals(PickerAction.Click(0), AccountPickerDetector.decide(untitled, canPressOk = false))
    }

    @Test
    fun `falls back to pressing OK when no account row can be pressed`() {
        val opaque = listOf(n("Who's watching?"), n("", clickable = true), n("Add account", clickable = true))
        assertEquals(PickerAction.PressOk, AccountPickerDetector.decide(opaque, canPressOk = true))
        assertEquals(PickerAction.None, AccountPickerDetector.decide(opaque, canPressOk = false))
    }

    @Test
    fun `does nothing on any other youtube screen`() {
        val playing = listOf(n("Live", clickable = true), n("Subscribe", clickable = true), n("More options", clickable = true))
        val blankGrid = List(9) { n("", clickable = true) }
        assertEquals(PickerAction.None, AccountPickerDetector.decide(playing, canPressOk = true))
        assertEquals(PickerAction.None, AccountPickerDetector.decide(blankGrid, canPressOk = true))
        assertFalse(AccountPickerDetector.isPicker(listOf(n("Guest star interview", clickable = true))))
    }

    @Test
    fun `throttle spaces tries and caps them per minute`() {
        val t = PickerThrottle(minGapMs = 3_000, maxAttempts = 3, windowMs = 60_000)
        assertTrue(t.tryAcquire(0))
        assertFalse(t.tryAcquire(1_000))
        assertTrue(t.tryAcquire(3_000))
        assertTrue(t.tryAcquire(6_000))
        assertFalse(t.tryAcquire(9_000))
        assertTrue(t.tryAcquire(61_000))
    }
}
