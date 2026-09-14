package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** App-wide settings, one small JSON file, same contract as ChannelRepository. */
class SettingsRepository(context: Context) {

    private val file = File(context.filesDir, "settings.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun current(): AppSettings = _settings.value

    suspend fun replace(settings: AppSettings): AppSettings {
        withContext(Dispatchers.IO) { file.writeText(json.encodeToString(settings)) }
        _settings.value = settings
        return settings
    }

    private fun load(): AppSettings = runCatching {
        if (file.exists()) json.decodeFromString<AppSettings>(file.readText()) else AppSettings()
    }.getOrElse { AppSettings() }
}
