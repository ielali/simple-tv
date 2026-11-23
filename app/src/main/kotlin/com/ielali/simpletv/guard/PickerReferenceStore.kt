package com.ielali.simpletv.guard

import android.content.Context
import com.ielali.simpletv.data.JsonStore
import kotlinx.serialization.Serializable
import java.io.File

/** The remembered look of YouTube's account screen, taught once from the config page. */
@Serializable
data class PickerReference(val fingerprint: Fingerprint? = null, val savedAt: Long = 0L)

class PickerReferenceStore(context: Context) {
    private val store = JsonStore(File(context.filesDir, "youtube_picker.json"), PickerReference.serializer()) { PickerReference() }
    fun current(): PickerReference = store.current()
    suspend fun save(fingerprint: Fingerprint, at: Long) = store.replace(PickerReference(fingerprint, at))
    suspend fun clear() = store.replace(PickerReference())
}

/** Lets the config server ask the running guard service to capture the screen. */
object GuardBridge {
    @Volatile var service: HomeGuardService? = null
}
