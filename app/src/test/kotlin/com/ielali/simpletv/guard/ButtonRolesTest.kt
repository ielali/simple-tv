package com.ielali.simpletv.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ButtonRolesTest {

    @Test
    fun `live tv, guide, info and spare buttons can take a job`() {
        listOf(GuardKeys.TV, GuardKeys.GUIDE, GuardKeys.INFO, GuardKeys.BOOKMARK, GuardKeys.PROG_BLUE, 1000).forEach {
            assertTrue("key $it", ButtonRoles.assignable(it))
        }
    }

    @Test
    fun `buttons with a fixed job never take another`() {
        listOf(GuardKeys.HOME, GuardKeys.BACK, GuardKeys.DIGIT_0, GuardKeys.DPAD_UP, GuardKeys.DPAD_CENTER,
            GuardKeys.VOLUME_UP, GuardKeys.MUTE, GuardKeys.POWER, GuardKeys.CHANNEL_UP, GuardKeys.MENU,
            GuardKeys.SETTINGS, GuardKeys.MEDIA_PLAY_PAUSE, 0, -5).forEach {
            assertFalse("key $it", ButtonRoles.assignable(it))
        }
    }

    @Test
    fun `one job per button, the channel list first, and a button with a job is not blocked`() {
        val a = ButtonRoles.sanitize(
            list = listOf(GuardKeys.GUIDE, GuardKeys.TV, GuardKeys.GUIDE, GuardKeys.HOME, GuardKeys.VOLUME_UP),
            previous = listOf(GuardKeys.TV, GuardKeys.BOOKMARK),
            blocked = listOf(GuardKeys.BOOKMARK, 1000, GuardKeys.BACK),
        )
        assertEquals(listOf(GuardKeys.TV, GuardKeys.GUIDE), a.list)
        assertEquals(listOf(GuardKeys.BOOKMARK), a.previous)
        assertEquals(listOf(1000), a.blocked)
    }

    @Test
    fun `the live tv button opens the list out of the box`() {
        assertEquals(ButtonRoles.Role.CHANNEL_LIST, ButtonRoles.roleOf(GuardKeys.TV, ButtonRoles.DEFAULT_LIST, emptyList()))
        assertEquals(ButtonRoles.Role.CHANNEL_LIST, ButtonRoles.roleOf(GuardKeys.GUIDE, ButtonRoles.DEFAULT_LIST, emptyList()))
        assertEquals(ButtonRoles.Role.PREVIOUS_CHANNEL, ButtonRoles.roleOf(174, emptyList(), listOf(174)))
        assertNull(ButtonRoles.roleOf(GuardKeys.INFO, ButtonRoles.DEFAULT_LIST, emptyList()))
    }
}
