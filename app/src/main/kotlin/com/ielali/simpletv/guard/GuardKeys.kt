package com.ielali.simpletv.guard

/**
 * Remote key codes the home guard cares about. Values mirror android.view.KeyEvent so this file stays
 * Android-free and JVM-testable; GuardKeysSdkTest checks every value against the SDK in CI.
 */
object GuardKeys {
    // Return to Simple TV
    const val HOME = 3
    const val TV = 170

    // App-launching and assistant keys: swallowed by default so a stray press opens nothing
    const val EXPLORER = 64
    const val ENVELOPE = 65
    const val SEARCH = 84
    const val DVR = 173
    const val BOOKMARK = 174
    const val TV_INPUT = 178
    const val PROG_RED = 183
    const val PROG_GREEN = 184
    const val PROG_YELLOW = 185
    const val PROG_BLUE = 186
    const val BUTTON_3 = 190 // used by some remotes for Netflix
    const val BUTTON_4 = 191 // used by some remotes for Prime Video
    const val CONTACTS = 207
    const val CALENDAR = 208
    const val MUSIC = 209
    const val CALCULATOR = 210
    const val ASSIST = 219
    const val VOICE_ASSIST = 231
    const val ALL_APPS = 284
    const val VIDEO_APP_1 = 289
    const val VIDEO_APP_8 = 296
    const val FEATURED_APP_1 = 297
    const val FEATURED_APP_4 = 300
    const val DEMO_APP_1 = 301
    const val DEMO_APP_4 = 304

    // Keys Simple TV, the YouTube app or the system need. The guard never blocks these.
    const val BACK = 4
    const val DIGIT_0 = 7
    const val DIGIT_9 = 16
    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val VOLUME_UP = 24
    const val VOLUME_DOWN = 25
    const val POWER = 26
    const val ENTER = 66
    const val MENU = 82
    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_REWIND = 89
    const val MEDIA_FAST_FORWARD = 90
    const val MUTE = 91
    const val PAGE_UP = 92
    const val PAGE_DOWN = 93
    const val ESCAPE = 111
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127
    const val NUMPAD_0 = 144
    const val NUMPAD_9 = 153
    const val NUMPAD_ENTER = 160
    const val VOLUME_MUTE = 164
    const val INFO = 165
    const val CHANNEL_UP = 166
    const val CHANNEL_DOWN = 167
    const val GUIDE = 172
    const val SETTINGS = 176
    const val TV_POWER = 177
    const val SLEEP = 223
    const val WAKEUP = 224

    val GO_HOME: Set<Int> = setOf(HOME, TV)

    val DEFAULT_BLOCKED: Set<Int> = setOf(
        EXPLORER, ENVELOPE, SEARCH, DVR, BOOKMARK, TV_INPUT,
        PROG_RED, PROG_GREEN, PROG_YELLOW, PROG_BLUE, BUTTON_3, BUTTON_4,
        CONTACTS, CALENDAR, MUSIC, CALCULATOR, ASSIST, VOICE_ASSIST, ALL_APPS,
    ) + (VIDEO_APP_1..VIDEO_APP_8) + (FEATURED_APP_1..FEATURED_APP_4) + (DEMO_APP_1..DEMO_APP_4)

    val PROTECTED: Set<Int> = setOf(
        BACK, DPAD_UP, DPAD_DOWN, DPAD_LEFT, DPAD_RIGHT, DPAD_CENTER, ENTER, NUMPAD_ENTER, ESCAPE,
        VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, MUTE, POWER, TV_POWER, SLEEP, WAKEUP,
        MENU, SETTINGS, INFO, GUIDE, CHANNEL_UP, CHANNEL_DOWN, PAGE_UP, PAGE_DOWN,
        MEDIA_PLAY_PAUSE, MEDIA_STOP, MEDIA_NEXT, MEDIA_PREVIOUS, MEDIA_REWIND, MEDIA_FAST_FORWARD,
        MEDIA_PLAY, MEDIA_PAUSE,
    ) + (DIGIT_0..DIGIT_9) + (NUMPAD_0..NUMPAD_9)
}
