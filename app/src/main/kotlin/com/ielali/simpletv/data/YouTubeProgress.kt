package com.ielali.simpletv.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.io.File

/**
 * Where each YouTube playlist channel stopped (ADR-023): the video, its place in the list and seconds
 * into it, so the channel carries on there after a channel change or a restart.
 */
class YouTubeProgress(context: Context) {
    @Serializable
    data class Spot(val index: Int = 0, val videoId: String? = null, val seconds: Int = 0, val at: Long = 0L)

    private val store = JsonStore(
        File(context.filesDir, "youtube_playlists.json"),
        MapSerializer(String.serializer(), Spot.serializer()),
    ) { emptyMap() }

    fun get(channelId: String): Spot? = store.current()[channelId]

    suspend fun save(channelId: String, spot: Spot) {
        if (store.current()[channelId]?.let { it.index == spot.index && it.videoId == spot.videoId && it.seconds == spot.seconds } == true) return
        store.replace(store.current() + (channelId to spot))
    }
}
