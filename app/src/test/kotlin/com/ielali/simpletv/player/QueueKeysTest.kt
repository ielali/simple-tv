package com.ielali.simpletv.player

import com.ielali.simpletv.data.PlaylistQueue
import com.ielali.simpletv.data.QueueEntry
import com.ielali.simpletv.guard.GuardKeys
import com.ielali.simpletv.player.QueueKeys.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QueueKeysTest {
    @Test
    fun `right goes to the next one`() {
        assertEquals(Action.NEXT, QueueKeys.action(GuardKeys.DPAD_RIGHT, 0))
        assertEquals(Action.NEXT, QueueKeys.action(GuardKeys.MEDIA_NEXT, 5_000))
    }

    @Test
    fun `left restarts, or goes back when it has only just started`() {
        assertEquals(Action.RESTART, QueueKeys.action(GuardKeys.DPAD_LEFT, 60_000))
        assertEquals(Action.PREVIOUS, QueueKeys.action(GuardKeys.DPAD_LEFT, 4_000))
        assertEquals(Action.PREVIOUS, QueueKeys.action(GuardKeys.DPAD_LEFT, QueueKeys.RESTART_WINDOW_MS))
    }

    @Test
    fun `ok and play-pause pause and carry on, up and down are left to channel changes`() {
        assertEquals(Action.TOGGLE_PAUSE, QueueKeys.action(GuardKeys.DPAD_CENTER, 0))
        assertEquals(Action.TOGGLE_PAUSE, QueueKeys.action(GuardKeys.MEDIA_PLAY_PAUSE, 0))
        assertNull(QueueKeys.action(GuardKeys.DPAD_UP, 0))
        assertNull(QueueKeys.action(GuardKeys.CHANNEL_DOWN, 0))
        assertEquals(Action.FORWARD, QueueKeys.action(GuardKeys.MEDIA_FAST_FORWARD, 0))
    }

    @Test
    fun `page actions by name`() {
        assertEquals(Action.NEXT, QueueKeys.fromName("next"))
        assertEquals(Action.PLAY, QueueKeys.fromName("resume"))
        assertNull(QueueKeys.fromName("explode"))
    }

    @Test
    fun `previous wraps from the first to the last`() {
        val q = (1..3).map { QueueEntry("$it", "Item $it", "", null, null) }
        assertEquals(2, PlaylistQueue.previousIndex(q, 0))
        assertEquals(0, PlaylistQueue.previousIndex(q, 1))
        assertEquals(0, PlaylistQueue.previousIndex(emptyList(), 0))
    }
}
