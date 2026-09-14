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

    fun next(current: Channel?, sorted: List<Channel>): Channel? {
        if (sorted.isEmpty()) return null
        if (current == null) return sorted.first()
        val idx = sorted.indexOfFirst { it.id == current.id }
        return sorted[(idx + 1).mod(sorted.size)]
    }

    fun previous(current: Channel?, sorted: List<Channel>): Channel? {
        if (sorted.isEmpty()) return null
        if (current == null) return sorted.last()
        val idx = sorted.indexOfFirst { it.id == current.id }
        return sorted[(idx - 1).mod(sorted.size)]
    }
}
