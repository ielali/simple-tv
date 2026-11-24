package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.flow.StateFlow
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
    val secrets: StateFlow<Secrets> = store.value
    fun current(): Secrets = store.current()
    suspend fun setPin(pin: String?) = store.replace(current().copy(pin = pin?.takeIf { it.isNotEmpty() }))
    suspend fun setYouTubeClient(id: String?, secret: String?) = store.replace(current().copy(ytClientId = id?.trim()?.takeIf { it.isNotEmpty() }, ytClientSecret = secret?.trim()?.takeIf { it.isNotEmpty() }))
    suspend fun setYouTubeRefresh(token: String?) = store.replace(current().copy(ytRefreshToken = token?.takeIf { it.isNotEmpty() }))
    suspend fun setTmdbKey(key: String?) = store.replace(current().copy(tmdbKey = key?.trim()?.takeIf { it.isNotEmpty() }))
    suspend fun setYoutubeKey(key: String?) = store.replace(current().copy(youtubeApiKey = key?.trim()?.takeIf { it.isNotEmpty() }))
}
