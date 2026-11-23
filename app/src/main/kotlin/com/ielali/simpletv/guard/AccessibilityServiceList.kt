package com.ielali.simpletv.guard

/**
 * Edits the colon-separated `enabled_accessibility_services` secure setting without disturbing other
 * services (tv-vibehack overwrote the whole value; this keeps whatever else the TV has enabled).
 */
object AccessibilityServiceList {

    /** `pkg/.Cls` and `pkg/pkg.Cls` name the same component. */
    fun normalise(entry: String): String {
        val e = entry.trim()
        val slash = e.indexOf('/')
        if (slash <= 0) return e
        val pkg = e.substring(0, slash)
        val cls = e.substring(slash + 1)
        return if (cls.startsWith(".")) "$pkg/$pkg$cls" else "$pkg/$cls"
    }

    fun parse(value: String?): List<String> =
        value.orEmpty().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }

    fun contains(value: String?, component: String): Boolean =
        parse(value).any { normalise(it) == normalise(component) }

    fun add(value: String?, component: String): String {
        val entries = parse(value)
        return if (contains(value, component)) entries.joinToString(":") else (entries + component).joinToString(":")
    }

    fun remove(value: String?, component: String): String =
        parse(value).filterNot { normalise(it) == normalise(component) }.joinToString(":")
}
