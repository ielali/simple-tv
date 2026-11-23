package com.ielali.simpletv.guard

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Test

/** GuardKeys copies android.view.KeyEvent values to stay JVM-pure; this keeps them honest. */
class GuardKeysSdkTest {

    @Test
    fun `guard key codes match the android sdk`() {
        val pairs = listOf(
            GuardKeys.HOME to KeyEvent.KEYCODE_HOME, GuardKeys.TV to KeyEvent.KEYCODE_TV,
            GuardKeys.EXPLORER to KeyEvent.KEYCODE_EXPLORER, GuardKeys.ENVELOPE to KeyEvent.KEYCODE_ENVELOPE,
            GuardKeys.SEARCH to KeyEvent.KEYCODE_SEARCH, GuardKeys.DVR to KeyEvent.KEYCODE_DVR,
            GuardKeys.BOOKMARK to KeyEvent.KEYCODE_BOOKMARK, GuardKeys.TV_INPUT to KeyEvent.KEYCODE_TV_INPUT,
            GuardKeys.PROG_RED to KeyEvent.KEYCODE_PROG_RED, GuardKeys.PROG_GREEN to KeyEvent.KEYCODE_PROG_GREEN,
            GuardKeys.PROG_YELLOW to KeyEvent.KEYCODE_PROG_YELLOW, GuardKeys.PROG_BLUE to KeyEvent.KEYCODE_PROG_BLUE,
            GuardKeys.BUTTON_3 to KeyEvent.KEYCODE_BUTTON_3, GuardKeys.BUTTON_4 to KeyEvent.KEYCODE_BUTTON_4,
            GuardKeys.CONTACTS to KeyEvent.KEYCODE_CONTACTS, GuardKeys.CALENDAR to KeyEvent.KEYCODE_CALENDAR,
            GuardKeys.MUSIC to KeyEvent.KEYCODE_MUSIC, GuardKeys.CALCULATOR to KeyEvent.KEYCODE_CALCULATOR,
            GuardKeys.ASSIST to KeyEvent.KEYCODE_ASSIST, GuardKeys.VOICE_ASSIST to KeyEvent.KEYCODE_VOICE_ASSIST,
            GuardKeys.ALL_APPS to KeyEvent.KEYCODE_ALL_APPS,
            GuardKeys.VIDEO_APP_1 to KeyEvent.KEYCODE_VIDEO_APP_1, GuardKeys.VIDEO_APP_8 to KeyEvent.KEYCODE_VIDEO_APP_8,
            GuardKeys.FEATURED_APP_1 to KeyEvent.KEYCODE_FEATURED_APP_1,
            GuardKeys.FEATURED_APP_4 to KeyEvent.KEYCODE_FEATURED_APP_4,
            GuardKeys.DEMO_APP_1 to KeyEvent.KEYCODE_DEMO_APP_1, GuardKeys.DEMO_APP_4 to KeyEvent.KEYCODE_DEMO_APP_4,
            GuardKeys.BACK to KeyEvent.KEYCODE_BACK, GuardKeys.DIGIT_0 to KeyEvent.KEYCODE_0,
            GuardKeys.DIGIT_9 to KeyEvent.KEYCODE_9, GuardKeys.DPAD_UP to KeyEvent.KEYCODE_DPAD_UP,
            GuardKeys.DPAD_DOWN to KeyEvent.KEYCODE_DPAD_DOWN, GuardKeys.DPAD_LEFT to KeyEvent.KEYCODE_DPAD_LEFT,
            GuardKeys.DPAD_RIGHT to KeyEvent.KEYCODE_DPAD_RIGHT, GuardKeys.DPAD_CENTER to KeyEvent.KEYCODE_DPAD_CENTER,
            GuardKeys.VOLUME_UP to KeyEvent.KEYCODE_VOLUME_UP, GuardKeys.VOLUME_DOWN to KeyEvent.KEYCODE_VOLUME_DOWN,
            GuardKeys.POWER to KeyEvent.KEYCODE_POWER, GuardKeys.ENTER to KeyEvent.KEYCODE_ENTER,
            GuardKeys.MENU to KeyEvent.KEYCODE_MENU, GuardKeys.MEDIA_PLAY_PAUSE to KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            GuardKeys.MEDIA_STOP to KeyEvent.KEYCODE_MEDIA_STOP, GuardKeys.MEDIA_NEXT to KeyEvent.KEYCODE_MEDIA_NEXT,
            GuardKeys.MEDIA_PREVIOUS to KeyEvent.KEYCODE_MEDIA_PREVIOUS,
            GuardKeys.MEDIA_REWIND to KeyEvent.KEYCODE_MEDIA_REWIND,
            GuardKeys.MEDIA_FAST_FORWARD to KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, GuardKeys.MUTE to KeyEvent.KEYCODE_MUTE,
            GuardKeys.PAGE_UP to KeyEvent.KEYCODE_PAGE_UP, GuardKeys.PAGE_DOWN to KeyEvent.KEYCODE_PAGE_DOWN,
            GuardKeys.ESCAPE to KeyEvent.KEYCODE_ESCAPE, GuardKeys.MEDIA_PLAY to KeyEvent.KEYCODE_MEDIA_PLAY,
            GuardKeys.MEDIA_PAUSE to KeyEvent.KEYCODE_MEDIA_PAUSE, GuardKeys.NUMPAD_0 to KeyEvent.KEYCODE_NUMPAD_0,
            GuardKeys.NUMPAD_9 to KeyEvent.KEYCODE_NUMPAD_9, GuardKeys.NUMPAD_ENTER to KeyEvent.KEYCODE_NUMPAD_ENTER,
            GuardKeys.VOLUME_MUTE to KeyEvent.KEYCODE_VOLUME_MUTE, GuardKeys.INFO to KeyEvent.KEYCODE_INFO,
            GuardKeys.CHANNEL_UP to KeyEvent.KEYCODE_CHANNEL_UP, GuardKeys.CHANNEL_DOWN to KeyEvent.KEYCODE_CHANNEL_DOWN,
            GuardKeys.GUIDE to KeyEvent.KEYCODE_GUIDE, GuardKeys.SETTINGS to KeyEvent.KEYCODE_SETTINGS,
            GuardKeys.TV_POWER to KeyEvent.KEYCODE_TV_POWER, GuardKeys.SLEEP to KeyEvent.KEYCODE_SLEEP,
            GuardKeys.WAKEUP to KeyEvent.KEYCODE_WAKEUP,
        )
        pairs.forEachIndexed { i, (ours, sdk) -> assertEquals("pair $i", sdk, ours) }
    }
}
