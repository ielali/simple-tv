package com.ielali.simpletv.data

import com.ielali.simpletv.guard.ButtonRoles
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Applies a partial settings update. The config page sends only the field it changed; replacing the
 * whole object would silently reset every other setting to its default.
 */
object SettingsMerge {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun merge(current: AppSettings, patch: JsonObject): AppSettings {
        val base = json.encodeToJsonElement(AppSettings.serializer(), current).jsonObject
        val merged = JsonObject(base + patch.filterKeys { it in base })
        return sanitize(json.decodeFromJsonElement(AppSettings.serializer(), merged))
    }

    fun sanitize(settings: AppSettings): AppSettings {
        val buttons = ButtonRoles.sanitize(settings.listButtons, settings.previousButtons, settings.guardBlockedKeys)
        return sanitizeRest(settings.copy(listButtons = buttons.list, previousButtons = buttons.previous, guardBlockedKeys = buttons.blocked))
    }

    private fun sanitizeRest(settings: AppSettings): AppSettings = settings.copy(
        guardPausedUntil = settings.guardPausedUntil.coerceAtLeast(0L),
        language = TvLanguage.normalise(settings.language),
        catalogRefreshTime = RefreshSchedule.normalise(settings.catalogRefreshTime),
        guideSources = settings.guideSources?.map { it.trim() }?.filter { s ->
            GuidePresets.find(s) != null || s.startsWith("https://", true) || s.startsWith("http://", true)
        }?.distinct()?.take(6),
        ratingCountry = settings.ratingCountry.trim().uppercase().takeIf { it.length == 2 && it.all(Char::isLetter) }.orEmpty(),
        previewMinutes = settings.previewMinutes.coerceIn(0, PreviewSessions.MAX_MINUTES),
    )
}
