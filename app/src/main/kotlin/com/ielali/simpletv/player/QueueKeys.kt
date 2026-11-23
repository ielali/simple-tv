package com.ielali.simpletv.player

import com.ielali.simpletv.guard.GuardKeys

/**
 * Remote keys on a film or series channel (ADR-019). Up/down and the channel rocker still change
 * channel; these only add moving within the channel. Pure so the mapping is unit tested.
 *
 * Right: next film or episode. Left: back to the start of this one, or to the previous one when it
 * has only just started. OK: pause and carry on. Media keys, on remotes that have them, do the same.
 */
object QueueKeys {
    enum class Action { NEXT, RESTART, PREVIOUS, TOGGLE_PAUSE, PLAY, PAUSE, FORWARD, BACK }

    /** Within this much of the start, Left goes to the previous item instead of restarting. */
    const val RESTART_WINDOW_MS = 10_000L
    const val JUMP_MS = 30_000L

    val KEYS = setOf(
        GuardKeys.DPAD_LEFT, GuardKeys.DPAD_RIGHT,
        GuardKeys.DPAD_CENTER, GuardKeys.ENTER, GuardKeys.NUMPAD_ENTER,
        GuardKeys.MEDIA_PLAY_PAUSE, GuardKeys.MEDIA_PLAY, GuardKeys.MEDIA_PAUSE,
        GuardKeys.MEDIA_NEXT, GuardKeys.MEDIA_PREVIOUS, GuardKeys.MEDIA_FAST_FORWARD, GuardKeys.MEDIA_REWIND,
    )

    fun action(keyCode: Int, positionMs: Long): Action? = when (keyCode) {
        GuardKeys.DPAD_RIGHT, GuardKeys.MEDIA_NEXT -> Action.NEXT
        GuardKeys.DPAD_LEFT, GuardKeys.MEDIA_PREVIOUS -> if (positionMs > RESTART_WINDOW_MS) Action.RESTART else Action.PREVIOUS
        GuardKeys.DPAD_CENTER, GuardKeys.ENTER, GuardKeys.NUMPAD_ENTER, GuardKeys.MEDIA_PLAY_PAUSE -> Action.TOGGLE_PAUSE
        GuardKeys.MEDIA_PLAY -> Action.PLAY
        GuardKeys.MEDIA_PAUSE -> Action.PAUSE
        GuardKeys.MEDIA_FAST_FORWARD -> Action.FORWARD
        GuardKeys.MEDIA_REWIND -> Action.BACK
        else -> null
    }

    /** Same actions from the config page's buttons. */
    fun fromName(name: String): Action? = when (name.lowercase()) {
        "next" -> Action.NEXT
        "restart" -> Action.RESTART
        "previous" -> Action.PREVIOUS
        "pause" -> Action.PAUSE
        "resume", "play" -> Action.PLAY
        else -> null
    }
}
