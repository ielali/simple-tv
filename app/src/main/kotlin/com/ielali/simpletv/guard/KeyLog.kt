package com.ielali.simpletv.guard

import kotlinx.serialization.Serializable

@Serializable
data class KeyLogEntry(
    val atMillis: Long,
    val keyCode: Int,
    val name: String,
    /** "guard" when the accessibility service saw it, "app" when Simple TV's own screen did. */
    val source: String,
    val action: String,
)

/**
 * Last few remote key presses, so a caregiver can press each button and read its code on the config
 * page instead of running adb logcat. Thread-safe; the guard writes on the main thread, the server reads.
 */
class KeyLog(private val capacity: Int = 40) {
    private val entries = ArrayDeque<KeyLogEntry>()

    @Synchronized
    fun record(entry: KeyLogEntry) {
        entries.addFirst(entry)
        while (entries.size > capacity) entries.removeLast()
    }

    /** Newest first. */
    @Synchronized
    fun entries(): List<KeyLogEntry> = entries.toList()

    @Synchronized
    fun clear() = entries.clear()

    companion object {
        val shared = KeyLog()
    }
}
