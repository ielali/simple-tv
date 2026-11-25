package com.ielali.simpletv.data

import java.net.URL

/**
 * Rewrites an HLS playlist for the page's preview player (ADR-029). Every address in it is swapped
 * for a name the TV made up, so the browser never sees the provider's address: that address holds
 * the subscription's username and password. Pure, so the rule is unit tested.
 */
object HlsRewrite {
    private val URI_ATTR = Regex("URI=\"([^\"]*)\"")
    private const val MARK = "#EXTM3U"

    /** True when a response that starts with [head] is a playlist rather than video. */
    fun isPlaylist(head: ByteArray, length: Int = head.size): Boolean {
        // Some servers put a byte-order mark first.
        val from = if (length >= 3 && head[0] == 0xEF.toByte() && head[1] == 0xBB.toByte() && head[2] == 0xBF.toByte()) 3 else 0
        if (length - from < MARK.length) return false
        return MARK.indices.all { head[from + it] == MARK[it].code.toByte() }
    }

    /**
     * [base] is where the playlist came from, after redirects; relative addresses are worked out
     * against it. [name] turns an absolute address into the made-up name the browser gets.
     */
    fun rewrite(playlist: String, base: String, name: (String) -> String): String {
        val from = URL(base)
        // A playlist that ends with a line end keeps exactly that one: its last "line" is the empty one after it.
        return playlist.removePrefix("﻿").lineSequence().mapNotNull { raw ->
            val line = raw.trim()
            when {
                line.isEmpty() -> ""
                line.startsWith("#") -> tag(line, from, name)
                else -> name(absolute(from, line))
            }
        }.joinToString("\n")
    }

    /** A tag with its addresses swapped; null when it still holds one somewhere we do not know to look. */
    private fun tag(line: String, from: URL, name: (String) -> String): String? {
        val swapped = URI_ATTR.replace(line) { m ->
            val address = m.groupValues[1]
            // A key written into the playlist itself is not an address.
            if (address.startsWith("data:", ignoreCase = true)) m.value else "URI=\"${name(absolute(from, address))}\""
        }
        if (!swapped.contains("://")) return swapped
        // A programme title may be a link: keep the segment's length, drop the title.
        if (swapped.startsWith("#EXTINF:")) return swapped.substringBefore(',') + ","
        return null
    }

    private fun absolute(from: URL, address: String): String = URL(from, address).toString()
}
