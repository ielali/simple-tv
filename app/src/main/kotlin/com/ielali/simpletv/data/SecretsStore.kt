package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Secrets the config page sets but never reads back: the page PIN (shown on the TV's own settings
 * screen), the optional YouTube Data API key (ADR-014) and the optional TMDB key (ADR-018). App-private storage only.
 */
class SecretsStore(context: Context) {
    @Serializable
    data class Secrets(
        val pin: String? = null,
        val youtubeApiKey: String? = null,
        val tmdbKey: String? = null,
        /** The household's OAuth client ("TVs and Limited Input devices") and the TV's sign-in (ADR-021). */
        val ytClientId: String? = null,
        val ytClientSecret: String? = null,
        val ytRefreshToken: String? = null,
    )

    private val store = JsonStore(File(context.filesDir, "secrets.json"), Secrets.serializer()) { Secrets() }
    private val lock = Mutex()
    val secrets: StateFlow<Secrets> = store.value
    fun current(): Secrets = store.current()

    /** Read, change and write as one step: two saves at once must never undo each other. */
    private suspend fun update(change: (Secrets) -> Secrets) = lock.withLock { store.replace(change(store.current())) }

    suspend fun setPin(pin: String?) = update { it.copy(pin = pin?.takeIf { p -> p.isNotEmpty() }) }
    suspend fun setYouTubeClient(id: String?, secret: String?) = update {
        it.copy(ytClientId = id?.trim()?.takeIf { v -> v.isNotEmpty() }, ytClientSecret = secret?.trim()?.takeIf { v -> v.isNotEmpty() })
    }
    suspend fun setYouTubeRefresh(token: String?) = update { it.copy(ytRefreshToken = token?.takeIf { v -> v.isNotEmpty() }) }
    suspend fun setTmdbKey(key: String?) = update { it.copy(tmdbKey = key?.trim()?.takeIf { v -> v.isNotEmpty() }) }
    suspend fun setYoutubeKey(key: String?) = update { it.copy(youtubeApiKey = key?.trim()?.takeIf { v -> v.isNotEmpty() }) }
}
