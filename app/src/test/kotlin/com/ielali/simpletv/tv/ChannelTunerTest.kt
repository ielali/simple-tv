package com.ielali.simpletv.tv

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ChannelTunerTest {

    @Test
    fun `single digit tunes after timeout`() = runTest {
        val tuner = ChannelTuner(backgroundScope, entryTimeoutMs = 1500)
        val received = mutableListOf<Int>()
        backgroundScope.launch { tuner.tuneRequests.toList(received) }
        runCurrent()

        tuner.onDigit(7)
        assertEquals("7", tuner.pendingDigits.value)
        advanceTimeBy(1499)
        assertTrue(received.isEmpty())
        advanceTimeBy(2)
        assertEquals(listOf(7), received)
        assertEquals("", tuner.pendingDigits.value)
    }

    @Test
    fun `second digit restarts the timeout`() = runTest {
        val tuner = ChannelTuner(backgroundScope, entryTimeoutMs = 1500)
        val received = mutableListOf<Int>()
        backgroundScope.launch { tuner.tuneRequests.toList(received) }
        runCurrent()

        tuner.onDigit(1)
        advanceTimeBy(1000)
        tuner.onDigit(2)
        advanceTimeBy(1000)
        assertTrue(received.isEmpty())
        advanceTimeBy(600)
        assertEquals(listOf(12), received)
    }

    @Test
    fun `three digits tune immediately`() = runTest {
        val tuner = ChannelTuner(backgroundScope, entryTimeoutMs = 1500, maxDigits = 3)
        val received = mutableListOf<Int>()
        backgroundScope.launch { tuner.tuneRequests.toList(received) }
        runCurrent()

        tuner.onDigit(1); tuner.onDigit(0); tuner.onDigit(5)
        runCurrent()
        assertEquals(listOf(105), received)
        assertEquals("", tuner.pendingDigits.value)
    }

    @Test
    fun `OK confirms pending digits at once`() = runTest {
        val tuner = ChannelTuner(backgroundScope)
        val received = mutableListOf<Int>()
        backgroundScope.launch { tuner.tuneRequests.toList(received) }
        runCurrent()

        assertFalse(tuner.onConfirm())
        tuner.onDigit(4)
        assertTrue(tuner.onConfirm())
        runCurrent()
        assertEquals(listOf(4), received)
        advanceTimeBy(5000)
        assertEquals(listOf(4), received)
    }

    @Test
    fun `cancel discards digits`() = runTest {
        val tuner = ChannelTuner(backgroundScope)
        val received = mutableListOf<Int>()
        backgroundScope.launch { tuner.tuneRequests.toList(received) }
        runCurrent()

        tuner.onDigit(9)
        tuner.onCancel()
        advanceTimeBy(5000)
        assertTrue(received.isEmpty())
        assertEquals("", tuner.pendingDigits.value)
    }
}
