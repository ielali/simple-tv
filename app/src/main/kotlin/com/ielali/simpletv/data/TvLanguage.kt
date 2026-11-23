package com.ielali.simpletv.data

/**
 * Languages the TV screen is translated into. "" means follow the device's system language.
 * Adding one: a values-<tag>/strings.xml with every translatable string, plus an entry here.
 */
object TvLanguage {
    const val SYSTEM = ""
    val SUPPORTED: Map<String, String> = linkedMapOf(
        "en" to "English",
        "ar" to "العربية",
    )

    /** Unknown or malformed tags fall back to the system language. */
    fun normalise(tag: String?): String = tag?.trim()?.lowercase()?.takeIf { it in SUPPORTED } ?: SYSTEM

    fun isRightToLeft(tag: String): Boolean = tag in setOf("ar", "fa", "he", "ur")
}
