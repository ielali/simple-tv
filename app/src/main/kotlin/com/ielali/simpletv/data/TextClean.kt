package com.ielali.simpletv.data

/**
 * Tidies text that providers send: HTML entities left in names ("Tom &amp; Jerry", sometimes encoded
 * twice), stray control characters and runs of spaces. Guide titles that are really a path or a link
 * (some providers fill empty guides with "/home/1/1...") are dropped, so the TV shows nothing rather
 * than rubbish.
 */
object TextClean {
    private val named = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ",
        "ndash" to "–", "mdash" to "—", "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "rdquo" to "”", "ldquo" to "“",
    )
    private val entity = Regex("&(#[0-9]{1,7}|#[xX][0-9a-fA-F]{1,6}|[a-zA-Z]{2,8});")
    private val spaces = Regex("\\s+")
    private val pathLike = Regex("^(?:[a-zA-Z][a-zA-Z0-9+.-]*://\\S*|/?[\\w.~%-]+(?:/[\\w.~%-]*)+)$")

    fun decodeEntities(s: String): String {
        var out = s
        // Twice-encoded text ("&amp;amp;") needs more than one pass.
        repeat(3) {
            if ('&' !in out) return out
            val next = entity.replace(out) { m ->
                val body = m.groupValues[1]
                when {
                    body.startsWith("#x") || body.startsWith("#X") -> body.substring(2).toIntOrNull(16)?.let(::codePoint)
                    body.startsWith("#") -> body.substring(1).toIntOrNull()?.let(::codePoint)
                    else -> named[body.lowercase()]
                } ?: m.value
            }
            if (next == out) return out
            out = next
        }
        return out
    }

    private fun codePoint(cp: Int): String? =
        if (cp in 1..0x10FFFF && cp !in 0xD800..0xDFFF) String(Character.toChars(cp)) else null

    /** A channel, film or series name as people should read it. */
    fun name(s: String): String =
        decodeEntities(s).map { if (it.isISOControl() || it == ' ') ' ' else it }.joinToString("")
            .replace(spaces, " ").trim()

    /** A guide title, or null when it is empty or only a path or link. */
    fun guideTitle(s: String?): String? {
        val t = name(s ?: return null)
        if (t.isEmpty() || pathLike.matches(t)) return null
        return t
    }

    /** A guide description: tidied, keeping line breaks out (the TV shows a few lines at most). */
    fun guideText(s: String?): String? = s?.let(::name)?.takeIf { it.isNotEmpty() && !pathLike.matches(it) }
}
