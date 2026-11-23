package com.ielali.simpletv.guard

import kotlinx.serialization.Serializable

/** One on-screen element as the guard sees it: its visible words (own or its children's) and whether it can be pressed. */
@Serializable
data class ScreenNode(val label: String, val clickable: Boolean, val focused: Boolean = false)

sealed interface PickerAction {
    data object None : PickerAction
    /** Press this node (index into the flattened screen). */
    data class Click(val index: Int) : PickerAction
    /** No pressable account found: press OK, which picks the focused (first) account. */
    data object PressOk : PickerAction
}

/**
 * Recognises YouTube's "Who's watching?" account screen and chooses the first account, which this
 * household uses as the main one. Conservative on purpose: it acts only when the screen's own words
 * say it is an account picker, and never presses "Add account", "Guest" or navigation items.
 */
object AccountPickerDetector {

    private val PICKER_TITLES = listOf(
        "who's watching", "who’s watching", "who is watching",
        "choose an account", "choose account", "select an account", "select account",
        "switch account", "pick an account",
        "من يشاهد",                 // من يشاهد
        "اختيار حساب", // اختيار حساب
        "اختر حساب",            // اختر حساب
    )

    private val NOT_AN_ACCOUNT = listOf(
        "add account", "add an account", "guest", "without an account", "sign in", "sign out",
        "settings", "manage", "back", "navigate up", "search", "more options", "cancel",
        "إضافة حساب", // إضافة حساب
        "ضيف",                                          // ضيف
        "تسجيل الدخول", // تسجيل الدخول
    )

    private fun String.has(any: List<String>) = lowercase().let { t -> any.any { t.contains(it) } }

    fun isPicker(nodes: List<ScreenNode>): Boolean {
        if (nodes.any { it.label.has(PICKER_TITLES) }) return true
        val hasAdd = nodes.any { it.label.has(listOf("add account", "add an account")) }
        val hasGuest = nodes.any { it.label.has(listOf("guest", "without an account")) }
        return hasAdd && hasGuest
    }

    fun decide(nodes: List<ScreenNode>, canPressOk: Boolean): PickerAction {
        if (!isPicker(nodes)) return PickerAction.None
        val index = nodes.indexOfFirst {
            it.clickable && it.label.isNotBlank() && !it.label.has(PICKER_TITLES) && !it.label.has(NOT_AN_ACCOUNT)
        }
        return when {
            index >= 0 -> PickerAction.Click(index)
            canPressOk -> PickerAction.PressOk
            else -> PickerAction.None
        }
    }
}

/** Limits picker presses: a gap between tries and a few tries per minute, so the guard can never loop. */
class PickerThrottle(
    private val minGapMs: Long = 3_000,
    private val maxAttempts: Int = 3,
    private val windowMs: Long = 60_000,
) {
    private val attempts = ArrayDeque<Long>()

    @Synchronized
    fun tryAcquire(now: Long): Boolean {
        while (attempts.isNotEmpty() && now - attempts.first() >= windowMs) attempts.removeFirst()
        if (attempts.isNotEmpty() && now - attempts.last() < minGapMs) return false
        if (attempts.size >= maxAttempts) return false
        attempts.addLast(now)
        return true
    }
}

/**
 * What the guard last saw on YouTube's screen after a hand-off, and what it did. Served at
 * /api/debug/youtube-screen so detection can be checked or tuned without adb. Local network only;
 * it can contain account names, as shown on the TV.
 */
object YouTubeScreenLog {
    @Serializable
    data class Snapshot(
        val seenAt: Long = 0L,
        val nodeCount: Int = 0,
        val nodes: List<ScreenNode> = emptyList(),
        val pickerDetected: Boolean = false,
        val lastActionAt: Long = 0L,
        val lastAction: String = "",
    )

    @Volatile var snapshot = Snapshot()
        private set

    fun recordScreen(at: Long, nodes: List<ScreenNode>, picker: Boolean) {
        snapshot = snapshot.copy(seenAt = at, nodeCount = nodes.size, nodes = nodes.take(80), pickerDetected = picker)
    }

    fun recordAction(at: Long, description: String) {
        snapshot = snapshot.copy(lastActionAt = at, lastAction = description)
    }
}
