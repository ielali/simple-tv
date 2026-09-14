package com.ielali.simpletv.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * Single source of truth for the channel list.
 *
 * Persisted as one JSON file in app-private storage. Small enough that rewriting the whole file on
 * every change is fine, and it keeps the config web UI's "PUT the whole list" contract trivial.
 */
class ChannelRepository(context: Context) {

    private val file = File(context.filesDir, "channels.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }
    private val mutex = Mutex()

    private val _channels = MutableStateFlow(load())
    val channels: StateFlow<ChannelList> = _channels.asStateFlow()

    fun current(): ChannelList = _channels.value

    /** Replace the whole list. Numbers are validated for uniqueness; ids are filled in when missing. */
    suspend fun replaceAll(list: ChannelList): ChannelList = mutex.withLock {
        val normalised = normalise(list)
        withContext(Dispatchers.IO) { file.writeText(json.encodeToString(normalised)) }
        _channels.value = normalised
        normalised
    }

    /** Append channels, giving each a free number after the current highest. */
    suspend fun append(newChannels: List<Channel>): ChannelList {
        val existing = current()
        var next = (existing.channels.maxOfOrNull { it.number } ?: 0) + 1
        val numbered = newChannels.map { c -> c.copy(number = next++) }
        return replaceAll(existing.copy(channels = existing.channels + numbered))
    }

    private fun normalise(list: ChannelList): ChannelList {
        val seen = HashSet<Int>()
        val fixed = list.channels
            .sortedBy { it.number }
            .map { c ->
                var n = c.number.coerceAtLeast(1)
                while (!seen.add(n)) n++
                c.copy(
                    id = c.id.ifBlank { UUID.randomUUID().toString() },
                    number = n,
                    name = c.name.trim().ifBlank { "Channel $n" },
                    url = c.url.trim(),
                )
            }
        return ChannelList(version = 1, channels = fixed)
    }

    private fun load(): ChannelList = runCatching {
        if (file.exists()) json.decodeFromString<ChannelList>(file.readText()) else ChannelList()
    }.getOrElse { ChannelList() }
}
