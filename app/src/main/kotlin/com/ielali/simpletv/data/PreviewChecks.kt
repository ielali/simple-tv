package com.ielali.simpletv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** What a preview showed about a channel's stream. */
@Serializable
enum class PreviewResult {
    /** The provider sent video. */
    WORKED,

    /** The provider refused or did not answer before any video came. */
    FAILED,

    /** Not an HLS stream over http: no browser player here can show it, whatever the TV can do. */
    UNPLAYABLE,
}

@Serializable
data class PreviewCheck(val result: PreviewResult, val at: Long)

/**
 * The marks the page's preview player leaves on channels (ADR-029): the last thing previewing each
 * one showed. Kept by subscription and source id where a channel has them, so a channel tried in a
 * subscription's own list keeps its mark once it is in the lineup; by channel id otherwise. One
 * small JSON file, rewritten on each change; memory only when [file] is null. Android-free.
 */
class PreviewChecks(private val file: File? = null, private val clock: () -> Long = System::currentTimeMillis) {

    @Serializable
    internal data class Saved(val checks: Map<String, PreviewCheck> = emptyMap())

    private val json = Json { ignoreUnknownKeys = true }
    private var checks: Map<String, PreviewCheck> = load()

    /** Notes [result] for [channel], in place of what an earlier preview showed. */
    @Synchronized
    fun record(channel: Channel, result: PreviewResult) {
        val next = LinkedHashMap(checks)
        next[key(channel)] = PreviewCheck(result, clock())
        // A caregiver tries tens of channels, not thousands; the oldest marks go when it is more.
        if (next.size > MAX) next.entries.sortedBy { it.value.at }.take(next.size - MAX).forEach { next.remove(it.key) }
        checks = next
        val to = file ?: return
        runCatching { to.writeText(json.encodeToString(Saved.serializer(), Saved(next))) }
    }

    @Synchronized
    fun of(channel: Channel): PreviewCheck? = checks[key(channel)]

    /** The marks of channels that belong to a subscription, by "subscription id/source id". */
    @Synchronized
    fun listed(): Map<String, PreviewCheck> = checks.filterKeys { !it.startsWith(OWN) }

    private fun load(): Map<String, PreviewCheck> = runCatching {
        if (file != null && file.exists()) json.decodeFromString(Saved.serializer(), file.readText()).checks else emptyMap()
    }.getOrElse { emptyMap() }

    companion object {
        const val MAX = 2000
        private const val OWN = "id:"

        fun key(channel: Channel): String {
            val sub = channel.providerId
            val source = channel.sourceId
            return if (sub != null && source != null) "$sub/$source" else OWN + channel.id
        }
    }
}
