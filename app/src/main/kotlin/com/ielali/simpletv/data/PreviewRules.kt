package com.ielali.simpletv.data

import java.net.URL

/**
 * May the page preview a channel while the TV is playing (ADR-029)? The TV always wins: many
 * subscriptions allow one stream at a time, and a second one can stop the picture on the TV.
 */
object PreviewRules {
    enum class Verdict { OK, TV_ON_THIS_CHANNEL, TV_USES_SUBSCRIPTION }

    /**
     * [tv] is what Telly's own player holds on the TV right now: the channel on screen and, with the
     * channel list open, the one in its preview window; empty in standby, on YouTube or with another
     * app in front. [limit] says how many streams at once a subscription allows, null when unknown.
     */
    fun check(preview: Channel, tv: List<Channel>, limit: (providerId: String) -> Int?): Verdict {
        var verdict = Verdict.OK
        for (on in tv) {
            if (on.id == preview.id || sameStream(preview, on)) return Verdict.TV_ON_THIS_CHANNEL
            if (sharesStream(preview, on, limit)) verdict = Verdict.TV_USES_SUBSCRIPTION
        }
        return verdict
    }

    /** A channel from a subscription's list is the one on the TV when it is the same stream, whatever its id. */
    private fun sameStream(preview: Channel, on: Channel): Boolean =
        (preview.url.isNotEmpty() && preview.url == on.url) ||
            (preview.providerId != null && preview.providerId == on.providerId && preview.sourceId != null && preview.sourceId == on.sourceId)

    private fun sharesStream(preview: Channel, on: Channel, limit: (String) -> Int?): Boolean {
        val tvSub = on.providerId ?: on.playlist?.providerId
        val pvSub = preview.providerId
        val sameServer = host(on.url) != null && host(on.url) == host(preview.url)
        return when {
            tvSub != null && tvSub == pvSub -> {
                val streams = limit(tvSub)
                // Unknown limit: the same server means the same account; a film channel has no address to compare.
                if (streams != null) streams < 2 else sameServer || on.playlist != null
            }
            // Two different subscriptions are two accounts.
            tvSub != null && pvSub != null -> false
            // A channel added by its link has no subscription to go by: the same server is taken as the same account.
            else -> sameServer
        }
    }

    private fun host(address: String): String? =
        runCatching { URL(address).host.lowercase() }.getOrNull()?.takeIf { it.isNotEmpty() }
}
