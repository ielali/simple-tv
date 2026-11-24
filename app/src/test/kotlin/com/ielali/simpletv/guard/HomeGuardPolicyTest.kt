package com.ielali.simpletv.guard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeGuardPolicyTest {

    private val on = GuardConfig()
    private val now = 1_000_000L

    @Test
    fun `home and tv keys return to simple tv`() {
        val noList = on.copy(listKeys = emptySet())
        assertEquals(KeyAction.GO_HOME, HomeGuardPolicy.onKey(GuardKeys.HOME, on, now))
        assertEquals(KeyAction.GO_HOME, HomeGuardPolicy.onKey(GuardKeys.TV, noList, now))
    }

    @Test
    fun `the live tv button opens the channel list from anywhere, over youtube too`() {
        assertEquals(KeyAction.CHANNEL_LIST, HomeGuardPolicy.onKey(GuardKeys.TV, on, now))
        assertEquals(KeyAction.CHANNEL_LIST, HomeGuardPolicy.onKey(GuardKeys.GUIDE, on, now))
        assertEquals(KeyAction.CHANNEL_LIST, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.GUIDE, on, now, false))
        assertEquals(KeyAction.CHANNEL_LIST, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.TV, on, now, true))
        // HOME keeps its job whatever the settings say
        assertEquals(KeyAction.GO_HOME, HomeGuardPolicy.onKey(GuardKeys.HOME, on, now))
    }

    @Test
    fun `a button given previous channel wins over blocking it`() {
        val cfg = on.copy(previousKeys = setOf(GuardKeys.BOOKMARK))
        assertEquals(KeyAction.SWALLOW, HomeGuardPolicy.onKey(GuardKeys.BOOKMARK, on, now))
        assertEquals(KeyAction.PREVIOUS_CHANNEL, HomeGuardPolicy.onKey(GuardKeys.BOOKMARK, cfg, now))
        assertEquals(KeyAction.PREVIOUS_CHANNEL, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.BOOKMARK, cfg, now, false))
    }

    @Test
    fun `button jobs do nothing while the guard is paused`() {
        val paused = on.copy(pausedUntil = now + 1)
        assertEquals(KeyAction.PASS, HomeGuardPolicy.onKey(GuardKeys.GUIDE, paused, now))
    }

    @Test
    fun `assistant colour and app keys are swallowed`() {
        listOf(GuardKeys.ASSIST, GuardKeys.SEARCH, GuardKeys.PROG_RED, GuardKeys.BUTTON_3, GuardKeys.ALL_APPS,
            GuardKeys.FEATURED_APP_1, GuardKeys.VIDEO_APP_8).forEach {
            assertEquals("key $it", KeyAction.SWALLOW, HomeGuardPolicy.onKey(it, on, now))
        }
    }

    @Test
    fun `keys simple tv and the system need always pass`() {
        listOf(GuardKeys.DIGIT_0, GuardKeys.DIGIT_9, GuardKeys.CHANNEL_UP, GuardKeys.DPAD_CENTER, GuardKeys.BACK,
            GuardKeys.VOLUME_UP, GuardKeys.VOLUME_MUTE, GuardKeys.POWER, GuardKeys.MENU, GuardKeys.SETTINGS,
            GuardKeys.MEDIA_PLAY_PAUSE).forEach {
            assertEquals("key $it", KeyAction.PASS, HomeGuardPolicy.onKey(it, on, now))
        }
    }

    @Test
    fun `caregiver blocked keys are swallowed but protected ones never`() {
        val cfg = on.copy(extraBlockedKeys = setOf(1000, GuardKeys.VOLUME_UP))
        assertEquals(KeyAction.SWALLOW, HomeGuardPolicy.onKey(1000, cfg, now))
        assertEquals(KeyAction.PASS, HomeGuardPolicy.onKey(GuardKeys.VOLUME_UP, cfg, now))
    }

    @Test
    fun `paused or disabled guard passes everything`() {
        val paused = on.copy(pausedUntil = now + 1)
        val off = on.copy(enabled = false)
        listOf(paused, off).forEach { cfg ->
            assertEquals(KeyAction.PASS, HomeGuardPolicy.onKey(GuardKeys.HOME, cfg, now))
            assertEquals(KeyAction.PASS, HomeGuardPolicy.onKey(GuardKeys.ASSIST, cfg, now))
            assertEquals(WindowAction.NONE, HomeGuardPolicy.onWindow(HomeGuardPolicy.STOCK_LAUNCHER, cfg, now))
        }
        assertTrue(HomeGuardPolicy.isActive(paused, now + 1))
    }

    @Test
    fun `stock home and sponsored apps are covered`() {
        assertEquals(WindowAction.GO_HOME, HomeGuardPolicy.onWindow(HomeGuardPolicy.STOCK_LAUNCHER, on, now))
        assertEquals(WindowAction.GO_HOME, HomeGuardPolicy.onWindow("com.netflix.ninja", on, now))
        assertEquals(WindowAction.GO_HOME, HomeGuardPolicy.onWindow(HomeGuardPolicy.YOUTUBE_TV, on, now))
    }

    @Test
    fun `youtube opened by simple tv is left alone`() {
        val handoff = on.copy(allowedExternalPackages = setOf(HomeGuardPolicy.YOUTUBE_TV))
        assertEquals(WindowAction.NONE, HomeGuardPolicy.onWindow(HomeGuardPolicy.YOUTUBE_TV, handoff, now))
        assertEquals(WindowAction.GO_HOME, HomeGuardPolicy.onWindow("com.netflix.ninja", handoff, now))
    }

    @Test
    fun `settings and other apps are never bounced`() {
        listOf("com.android.tv.settings", HomeGuardPolicy.OWN_PACKAGE, "com.android.systemui", null).forEach {
            assertEquals("pkg $it", WindowAction.NONE, HomeGuardPolicy.onWindow(it, on, now))
        }
    }

    @Test
    fun `stock home is watched but never in the blockable or protected key sets`() {
        assertTrue(HomeGuardPolicy.STOCK_LAUNCHER in HomeGuardPolicy.WATCHED_PACKAGES)
        assertTrue((GuardKeys.DEFAULT_BLOCKED intersect GuardKeys.PROTECTED).isEmpty())
        assertTrue((GuardKeys.GO_HOME intersect GuardKeys.PROTECTED).isEmpty())
    }

    @Test
    fun `sanitize keeps only blockable keys, sorted and unique`() {
        assertEquals(
            listOf(500, 1000),
            HomeGuardPolicy.sanitizeBlockedKeys(listOf(1000, GuardKeys.HOME, GuardKeys.DIGIT_0, 500, 1000, -1)),
        )
        assertFalse(HomeGuardPolicy.isBlockable(GuardKeys.VOLUME_DOWN))
        assertTrue(HomeGuardPolicy.isBlockable(1000))
    }

    @Test
    fun `debouncer lets one trigger through per window`() {
        val d = Debouncer(windowMs = 400)
        assertTrue(d.tryAcquire(10))
        assertFalse(d.tryAcquire(300))
        assertTrue(d.tryAcquire(410))
    }

    @Test
    fun `over youtube channel keys are routed and back and menu do nothing`() {
        listOf(GuardKeys.DIGIT_0, GuardKeys.CHANNEL_UP, GuardKeys.DPAD_DOWN, GuardKeys.INFO, GuardKeys.HOME,
            GuardKeys.BACK).forEach {
            assertEquals("key $it", KeyAction.ROUTE, HomeGuardPolicy.onKeyDuringExternal(it, on, now, digitsPending = false))
        }
        assertEquals(KeyAction.SWALLOW, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.MENU, on, now, false))
        assertEquals(KeyAction.SWALLOW, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.ASSIST, on, now, false))
    }

    @Test
    fun `over youtube ok controls the video unless digits are pending`() {
        assertEquals(KeyAction.PASS, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.DPAD_CENTER, on, now, false))
        assertEquals(KeyAction.ROUTE, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.DPAD_CENTER, on, now, true))
        listOf(GuardKeys.DPAD_LEFT, GuardKeys.MEDIA_PLAY_PAUSE, GuardKeys.VOLUME_UP).forEach {
            assertEquals("key $it", KeyAction.PASS, HomeGuardPolicy.onKeyDuringExternal(it, on, now, false))
        }
    }

    @Test
    fun `paused guard leaves youtube alone`() {
        val paused = on.copy(pausedUntil = now + 1)
        assertEquals(KeyAction.PASS, HomeGuardPolicy.onKeyDuringExternal(GuardKeys.CHANNEL_UP, paused, now, false))
    }
}
