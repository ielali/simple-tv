package com.ielali.simpletv.tv

import com.ielali.simpletv.data.Channel

/** Pure functions that pick the next channel. Kept separate from Android so they are trivially testable. */
object ChannelNavigator {

    /**
     * Resolve a typed number. Exact match wins; otherwise the next higher number, wrapping to the lowest.
     * Old TVs showed static on an empty number; we instead land on the nearest real channel so the viewer
     * is never stuck on a blank screen.
     */
    fun resolve(number: Int, sorted: List<Channel>): Channel? {
        if (sorted.isEmpty()) return null
        sorted.firstOrNull { it.number == number }?.let { return it }
        return sorted.firstOrNull { it.number > number } ?: sorted.first()
    }

    /**
     * Channel up. [skip] steps over channels that just failed to play (ADR-024); when every other
     * channel would be skipped, the plain next one is used, so the viewer is never stuck.
     */
    fun next(current: Channel?, sorted: List<Channel>, skip: (Channel) -> Boolean = { false }): Channel? =
        step(current, sorted, 1, skip)

    fun previous(current: Channel?, sorted: List<Channel>, skip: (Channel) -> Boolean = { false }): Channel? =
        step(current, sorted, -1, skip)

    private fun step(current: Channel?, sorted: List<Channel>, dir: Int, skip: (Channel) -> Boolean): Channel? {
        if (sorted.isEmpty()) return null
        val idx = current?.let { c -> sorted.indexOfFirst { it.id == c.id } } ?: -1
        val start = if (idx < 0) (if (dir > 0) -1 else 0) else idx
        val plain = sorted[(start + dir).mod(sorted.size)]
        for (k in 1..sorted.size) {
            val c = sorted[(start + dir * k).mod(sorted.size)]
            if (c.id == current?.id) break
            if (!skip(c)) return c
        }
        return plain
    }
}
