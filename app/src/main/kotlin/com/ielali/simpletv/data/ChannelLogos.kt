package com.ielali.simpletv.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * Logos for channels the provider gave none (ADR-027), from iptv-org's public-domain channel database
 * (https://github.com/iptv-org/api: channels.json and logos.json). Pure: the TV downloads the two
 * files, this keeps a small index of channels that have a logo, and finds one for a lineup channel by
 * its guide id, then by its tidied name, preferring the TV's country. When a name could be several
 * channels and the country doesn't settle it, nothing is chosen: no logo beats a wrong one.
 */
object ChannelLogos {
    @Serializable
    data class Index(
        /** Tidied channel name -> iptv-org ids. */
        val byName: Map<String, List<String>> = emptyMap(),
        /** iptv-org id (lower case) -> logo address. */
        val logo: Map<String, String> = emptyMap(),
        /** iptv-org id (lower case) -> country code. */
        val country: Map<String, String> = emptyMap(),
        val builtAt: Long = 0L,
        /** iptv-org id (lower case) -> the channel's name, for the logo picker. */
        val name: Map<String, String> = emptyMap(),
    )

    private data class Candidate(val url: String, val inUse: Boolean, val main: Boolean, val raster: Boolean, val width: Int)

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    /** The best logo per channel: still in use, the channel's main one, a picture the TV can draw (not SVG), the largest. */
    fun bestLogos(logos: Sequence<JsonObject>): Map<String, String> {
        val best = HashMap<String, Candidate>()
        for (o in logos) {
            val ch = o.str("channel")?.lowercase() ?: continue
            val url = o.str("url")?.takeIf { it.startsWith("https://") || it.startsWith("http://") } ?: continue
            val c = Candidate(
                url = url,
                inUse = (o["in_use"] as? JsonPrimitive)?.booleanOrNull != false,
                main = o.str("feed") == null,
                raster = o.str("format")?.uppercase() != "SVG" && !url.substringBefore('?').endsWith(".svg", ignoreCase = true),
                width = (o["width"] as? JsonPrimitive)?.intOrNull ?: 0,
            )
            val old = best[ch]
            if (old == null || rank(c) > rank(old)) best[ch] = c
        }
        return best.mapValues { it.value.url }
    }

    private fun rank(c: Candidate): Long =
        (if (c.raster) 8_000_000L else 0L) + (if (c.inUse) 4_000_000L else 0L) + (if (c.main) 2_000_000L else 0L) + c.width.coerceIn(0, 1_999_999)

    /** Index of the channels that have a logo, by every name they go by. */
    fun build(channels: Sequence<JsonObject>, logos: Map<String, String>, now: Long): Index {
        val byName = HashMap<String, MutableList<String>>()
        val country = HashMap<String, String>()
        val display = HashMap<String, String>()
        val kept = HashSet<String>()
        for (o in channels) {
            val id = o.str("id")?.lowercase() ?: continue
            if (id !in logos) continue
            if ((o["is_nsfw"] as? JsonPrimitive)?.booleanOrNull == true || o.str("closed") != null) continue
            kept += id
            o.str("country")?.let { country[id] = it.uppercase() }
            o.str("name")?.let { display[id] = it }
            val names = listOfNotNull(o.str("name")) + (o["alt_names"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
            for (n in names) {
                val k = GuideMatch.normalise(n)
                if (k.isNotEmpty()) byName.getOrPut(k) { ArrayList() }.let { if (id !in it) it += id }
            }
        }
        return Index(byName, logos.filterKeys { it in kept }, country, now, display)
    }

    /** A logo the caregiver can pick in the edit panel. */
    @Serializable
    data class Choice(val url: String, val label: String, val source: String)

    /** Channels whose name contains [q] (exact names first), for the caregiver's logo picker. */
    fun search(q: String, idx: Index, region: String, limit: Int = 12): List<Choice> {
        val k = GuideMatch.normalise(q)
        if (k.length < 2) return emptyList()
        val keys = idx.byName.keys.filter { it.contains(k) }.sortedWith(compareBy({ it != k }, { !it.startsWith(k) }, { it.length }))
        val home = countryCode(region)
        val ids = LinkedHashSet<String>()
        for (key in keys) {
            idx.byName[key].orEmpty().sortedBy { idx.country[it] != home }.forEach { if (it in idx.logo) ids += it }
            if (ids.size >= limit) break
        }
        return ids.take(limit).map { id ->
            Choice(idx.logo.getValue(id), (idx.name[id] ?: id.substringBeforeLast('.')) + (idx.country[id]?.let { " ($it)" } ?: ""), "iptv-org")
        }
    }

    /** Android says GB where iptv-org says UK. */
    fun countryCode(region: String): String = region.uppercase().let { if (it == "GB") "UK" else it }

    /** A logo for [ch]: by its guide id when that is an iptv-org id, else by name (in [region] when ambiguous). */
    fun find(ch: Channel, idx: Index, region: String): String? {
        ch.epgId?.lowercase()?.let { id -> idx.logo[id]?.let { return it } }
        val ids = idx.byName[GuideMatch.normalise(ch.name)].orEmpty().filter { it in idx.logo }
        val pick = when {
            ids.isEmpty() -> null
            ids.size == 1 -> ids[0]
            else -> ids.filter { idx.country[it] == countryCode(region) }.singleOrNull()
        }
        return pick?.let { idx.logo[it] }
    }
}
