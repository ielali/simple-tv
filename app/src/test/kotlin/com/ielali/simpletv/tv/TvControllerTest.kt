package com.ielali.simpletv.tv

import com.ielali.simpletv.data.AppSettings
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.ChannelList
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.data.YouTubePlayback
import com.ielali.simpletv.guard.GuardKeys
import com.ielali.simpletv.guard.GuardState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TvControllerTest {

    private class FakeLauncher(var opens: Boolean = true) : ExternalLauncher {
        val opened = mutableListOf<Int>()
        var broughtToFront = 0
        override fun open(channel: Channel): Boolean { opened += channel.number; return opens }
        override fun bringAppToFront() { broughtToFront++ }
    }

    private class FakeStore(var value: Int? = null) : LastChannelStore {
        override fun get() = value
        override fun put(number: Int) { value = number }
    }

    private fun yt(n: Int) = Channel(id = "y$n", number = n, name = "YT $n", type = SourceType.YOUTUBE, url = "dQw4w9WgXc$n")
    private fun stream(n: Int) = Channel(id = "s$n", number = n, name = "TV $n", url = "http://x/$n.m3u8")

    private val list = ChannelList(channels = listOf(stream(1), yt(2), yt(3), stream(4)))
    private val appMode = AppSettings(youtubePlayback = YouTubePlayback.YOUTUBE_APP)

    private class Rig(
        val controller: TvController,
        val launcher: FakeLauncher,
        val store: FakeStore,
        val settings: MutableStateFlow<AppSettings>,
    )

    private fun TestScope.rig(
        settings: AppSettings = appMode,
        last: Int? = null,
        launcher: FakeLauncher = FakeLauncher(),
    ): Rig {
        val s = MutableStateFlow(settings)
        val store = FakeStore(last)
        val c = TvController(
            backgroundScope, MutableStateFlow(list), s, launcher, store,
            clock = { testScheduler.currentTime }, entryTimeoutMs = 1500, bannerMs = 3500, reopenGraceMs = 5000,
        )
        runCurrent()
        return Rig(c, launcher, store, s)
    }

    @Before
    fun reset() { GuardState.allowedExternalPackages = emptySet() }

    @Test
    fun `restores the last channel silently`() = runTest {
        val r = rig(last = 3)
        assertEquals(3, r.controller.current.value?.number)
        assertTrue(r.launcher.opened.isEmpty())
        assertEquals(TvController.YOUTUBE_PACKAGES, GuardState.allowedExternalPackages)
    }

    @Test
    fun `screen becoming visible opens a youtube-app channel once per grace period`() = runTest {
        val r = rig(last = 2)
        r.controller.onAppVisible(true)
        assertEquals(listOf(2), r.launcher.opened)
        r.controller.onAppVisible(false)
        r.controller.onAppVisible(true)
        assertEquals(listOf(2), r.launcher.opened)
        advanceTimeBy(5001)
        r.controller.onAppVisible(true)
        assertEquals(listOf(2, 2), r.launcher.opened)
    }

    @Test
    fun `digits over youtube switch straight to the next youtube channel`() = runTest {
        val r = rig(last = 2)
        r.controller.onAppVisible(true); r.controller.onAppVisible(false) // YouTube now in front
        assertTrue(r.controller.isExternalFront())
        r.controller.onRemoteKey(GuardKeys.DIGIT_0 + 3)
        advanceTimeBy(1501)
        assertEquals(3, r.controller.current.value?.number)
        assertEquals(listOf(2, 3), r.launcher.opened)
        assertEquals(0, r.launcher.broughtToFront)
    }

    @Test
    fun `channel up from youtube to a stream brings simple tv forward`() = runTest {
        val r = rig(last = 3)
        r.controller.onAppVisible(true); r.controller.onAppVisible(false)
        r.controller.onRemoteKey(GuardKeys.CHANNEL_UP)
        assertEquals(4, r.controller.current.value?.number)
        assertEquals(1, r.launcher.broughtToFront)
        assertTrue(GuardState.allowedExternalPackages.isEmpty())
        assertFalse(r.controller.isExternalFront())
    }

    @Test
    fun `stream to youtube while on screen opens youtube without bouncing`() = runTest {
        val r = rig(last = 1)
        r.controller.onAppVisible(true)
        r.controller.onRemoteKey(GuardKeys.DPAD_UP)
        assertEquals(listOf(2), r.launcher.opened)
        assertEquals(0, r.launcher.broughtToFront)
        assertEquals(TvController.YOUTUBE_PACKAGES, GuardState.allowedExternalPackages)
    }

    @Test
    fun `failed youtube open falls back to the embed`() = runTest {
        val r = rig(last = 1, launcher = FakeLauncher(opens = false))
        r.controller.onAppVisible(true)
        r.controller.onRemoteKey(GuardKeys.CHANNEL_UP)
        assertTrue(r.controller.externalUnavailable.value)
        assertFalse(r.controller.usesExternalPlayer(r.controller.current.value))
        assertTrue(GuardState.allowedExternalPackages.isEmpty())
    }

    @Test
    fun `embedded mode never opens the youtube app`() = runTest {
        val r = rig(settings = AppSettings(), last = 1)
        r.controller.onAppVisible(true)
        r.controller.onRemoteKey(GuardKeys.CHANNEL_UP)
        assertEquals(2, r.controller.current.value?.number)
        assertTrue(r.launcher.opened.isEmpty())
    }

    @Test
    fun `switching to app mode while watching a youtube channel opens it`() = runTest {
        val r = rig(settings = AppSettings(), last = 2)
        r.controller.onAppVisible(true)
        r.settings.value = appMode
        runCurrent()
        assertEquals(listOf(2), r.launcher.opened)
    }

    @Test
    fun `home over youtube shows the banner and back cancels digits`() = runTest {
        val r = rig(last = 2)
        r.controller.onRemoteKey(GuardKeys.HOME)
        runCurrent()
        assertTrue(r.controller.bannerVisible.value)
        advanceTimeBy(3501); runCurrent()
        assertFalse(r.controller.bannerVisible.value)

        r.controller.onRemoteKey(GuardKeys.DIGIT_0 + 4)
        r.controller.onRemoteKey(GuardKeys.BACK)
        advanceTimeBy(2000)
        assertEquals(2, r.controller.current.value?.number)
    }

    @Test
    fun `switching remembers the channel`() = runTest {
        val r = rig(last = 1)
        r.controller.onAppVisible(true)
        r.controller.onRemoteKey(GuardKeys.CHANNEL_DOWN)
        assertEquals(4, r.store.value)
    }
}
