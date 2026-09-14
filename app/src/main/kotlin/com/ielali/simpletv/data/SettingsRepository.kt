package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** App-wide settings, one small JSON file. */
class SettingsRepository(context: Context) {
    private val store = JsonStore(File(context.filesDir, "settings.json"), AppSettings.serializer()) { AppSettings() }
    val settings: StateFlow<AppSettings> = store.value
    fun current(): AppSettings = store.current()
    suspend fun replace(settings: AppSettings): AppSettings = store.replace(settings)
}
