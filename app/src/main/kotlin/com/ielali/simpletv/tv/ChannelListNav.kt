package com.ielali.simpletv.tv

import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.guard.GuardKeys

/**
 * The channel list (ADR-022), as pure functions: which row is highlighted, what each remote key does
 * while the list is open, and which rows fit on screen. Up and Down move one channel and wrap round;
 * channel up/down, Left and Right move a page; OK watches the highlighted channel; BACK closes.
 */
object ChannelListNav {
    sealed interface Step {
        data class Highlight(val index: Int) : Step
        data class Watch(val index: Int) : Step
        /** A digit closes the list and starts typing a number, as it would without the list. */
        data class Digit(val digit: Int) : Step
        data object Close : Step
        /** Not the list's key (volume, power...): let it through. */
        data object Pass : Step
        /** Consumed without effect, so it does nothing behind the list. */
        data object Ignore : Step
    }

    /** The row to start on: the channel being watched, else the first. */
    fun start(current: Channel?, sorted: List<Channel>): Int =
        sorted.indexOfFirst { it.id == current?.id }.coerceAtLeast(0)

    fun onKey(keyCode: Int, index: Int, size: Int, page: Int): Step {
        if (size <= 0) return Step.Close
        return when (keyCode) {
            GuardKeys.DPAD_UP -> Step.Highlight(step(index, size, -1))
            GuardKeys.DPAD_DOWN -> Step.Highlight(step(index, size, 1))
            GuardKeys.CHANNEL_UP, GuardKeys.PAGE_UP, GuardKeys.DPAD_LEFT -> Step.Highlight(page(index, size, -page))
            GuardKeys.CHANNEL_DOWN, GuardKeys.PAGE_DOWN, GuardKeys.DPAD_RIGHT -> Step.Highlight(page(index, size, page))
            GuardKeys.DPAD_CENTER, GuardKeys.ENTER, GuardKeys.NUMPAD_ENTER -> Step.Watch(index.coerceIn(0, size - 1))
            GuardKeys.BACK, GuardKeys.ESCAPE -> Step.Close
            in GuardKeys.DIGIT_0..GuardKeys.DIGIT_9 -> Step.Digit(keyCode - GuardKeys.DIGIT_0)
            in GuardKeys.NUMPAD_0..GuardKeys.NUMPAD_9 -> Step.Digit(keyCode - GuardKeys.NUMPAD_0)
            GuardKeys.INFO, GuardKeys.GUIDE, GuardKeys.TV,
            GuardKeys.MEDIA_PLAY_PAUSE, GuardKeys.MEDIA_PLAY, GuardKeys.MEDIA_PAUSE,
            GuardKeys.MEDIA_NEXT, GuardKeys.MEDIA_PREVIOUS, GuardKeys.MEDIA_FAST_FORWARD, GuardKeys.MEDIA_REWIND -> Step.Ignore
            else -> Step.Pass
        }
    }

    /** One row up or down, wrapping round like channel up/down does. */
    fun step(index: Int, size: Int, delta: Int): Int = (index + delta).mod(size)

    /** A page up or down stops at the ends; from an end it wraps round to the other. */
    fun page(index: Int, size: Int, delta: Int): Int {
        val last = size - 1
        return when {
            delta > 0 && index >= last -> 0
            delta < 0 && index <= 0 -> last
            else -> (index + delta).coerceIn(0, last)
        }
    }

    /** First row to draw so the highlighted row sits in the middle where it can. */
    fun windowStart(index: Int, size: Int, rows: Int): Int {
        if (rows <= 0 || size <= rows) return 0
        return (index - rows / 2).coerceIn(0, size - rows)
    }
}
