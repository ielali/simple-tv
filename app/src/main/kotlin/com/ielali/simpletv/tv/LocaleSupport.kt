package com.ielali.simpletv.tv

import android.content.Context
import android.content.res.Configuration
import com.ielali.simpletv.data.TvLanguage
import java.util.Locale

/**
 * Applies the TV language chosen on the config page to Simple TV's own screens only, leaving the
 * device's system language alone. Works on every Android version (no per-app-locale API needed).
 */
object LocaleSupport {
    fun wrap(base: Context, languageTag: String): Context {
        val tag = TvLanguage.normalise(languageTag)
        if (tag == TvLanguage.SYSTEM) return base
        val locale = Locale.forLanguageTag(tag)
        val config = Configuration(base.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale) // Arabic lays out right to left
        }
        return base.createConfigurationContext(config)
    }
}
