package com.ielali.simpletv.tv

import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.guard.GuardKeys
import com.ielali.simpletv.tv.ChannelListNav.Step
import org.junit.Assert.assertEquals
import org.junit.Test

class ChannelListNavTest {

    private fun ch(n: Int) = Channel(id = "c$n", number = n, name = "TV $n", url = "http://x/$n")
    private val sorted = (1..12).map(::ch)

    @Test
    fun `opens on the channel being watched, else the first`() {
        assertEquals(4, ChannelListNav.start(ch(5), sorted))
        assertEquals(0, ChannelListNav.start(null, sorted))
        assertEquals(0, ChannelListNav.start(ch(99), sorted))
    }

    @Test
    fun `up and down move one row and wrap round`() {
        assertEquals(Step.Highlight(4), ChannelListNav.onKey(GuardKeys.DPAD_DOWN, 3, 12, 5))
        assertEquals(Step.Highlight(0), ChannelListNav.onKey(GuardKeys.DPAD_DOWN, 11, 12, 5))
        assertEquals(Step.Highlight(11), ChannelListNav.onKey(GuardKeys.DPAD_UP, 0, 12, 5))
    }

    @Test
    fun `channel keys and left right move a page, stopping at the ends before wrapping`() {
        assertEquals(Step.Highlight(8), ChannelListNav.onKey(GuardKeys.CHANNEL_DOWN, 3, 12, 5))
        assertEquals(Step.Highlight(11), ChannelListNav.onKey(GuardKeys.DPAD_RIGHT, 8, 12, 5))
        assertEquals(Step.Highlight(0), ChannelListNav.onKey(GuardKeys.PAGE_DOWN, 11, 12, 5))
        assertEquals(Step.Highlight(0), ChannelListNav.onKey(GuardKeys.CHANNEL_UP, 3, 12, 5))
        assertEquals(Step.Highlight(11), ChannelListNav.onKey(GuardKeys.DPAD_LEFT, 0, 12, 5))
    }

    @Test
    fun `ok watches, back closes, digits start typing a number`() {
        assertEquals(Step.Watch(6), ChannelListNav.onKey(GuardKeys.DPAD_CENTER, 6, 12, 5))
        assertEquals(Step.Watch(6), ChannelListNav.onKey(GuardKeys.ENTER, 6, 12, 5))
        assertEquals(Step.Close, ChannelListNav.onKey(GuardKeys.BACK, 6, 12, 5))
        assertEquals(Step.Digit(7), ChannelListNav.onKey(GuardKeys.DIGIT_0 + 7, 6, 12, 5))
        assertEquals(Step.Digit(3), ChannelListNav.onKey(GuardKeys.NUMPAD_0 + 3, 6, 12, 5))
    }

    @Test
    fun `volume and power pass through, media keys do nothing behind the list`() {
        assertEquals(Step.Pass, ChannelListNav.onKey(GuardKeys.VOLUME_UP, 6, 12, 5))
        assertEquals(Step.Pass, ChannelListNav.onKey(GuardKeys.POWER, 6, 12, 5))
        assertEquals(Step.Ignore, ChannelListNav.onKey(GuardKeys.MEDIA_PLAY_PAUSE, 6, 12, 5))
        assertEquals(Step.Close, ChannelListNav.onKey(GuardKeys.DPAD_DOWN, 0, 0, 5))
    }

    @Test
    fun `the highlighted row sits in the middle of the window where it can`() {
        assertEquals(0, ChannelListNav.windowStart(1, 12, 5))
        assertEquals(4, ChannelListNav.windowStart(6, 12, 5))
        assertEquals(7, ChannelListNav.windowStart(11, 12, 5))
        assertEquals(0, ChannelListNav.windowStart(3, 4, 5))
    }
}
