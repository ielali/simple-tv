package com.ielali.simpletv.tv

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.ielali.simpletv.MainActivity
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.guard.HomeGuardPolicy
import com.ielali.simpletv.youtube.YouTubeEmbed

/**
 * Android side of [ExternalLauncher]. Uses the application context so it works while Simple TV is in
 * the background (the YouTube app in front); the enabled home guard is what lets Android allow it.
 */
class ExternalPlayer(private val context: Context) : ExternalLauncher {

    /** The TV is off (standby): opening an app now would switch it back on. */
    private fun asleep(): Boolean =
        context.getSystemService(android.os.PowerManager::class.java)?.isInteractive == false

    override fun open(channel: Channel): Boolean {
        val target = YouTubeEmbed.parse(channel.url) ?: return false
        // In standby nothing opens; the channel opens when the TV comes back on and Simple TV shows.
        if (asleep()) return true
        val uri = Uri.parse(YouTubeEmbed.watchUrl(target))
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, uri).setPackage(HomeGuardPolicy.YOUTUBE_TV),
            Intent(Intent.ACTION_VIEW, uri).setPackage(HomeGuardPolicy.YOUTUBE_MOBILE),
            Intent(Intent.ACTION_VIEW, uri),
        )
        for (intent in candidates) {
            try {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return true
            } catch (e: ActivityNotFoundException) {
                // try the next candidate
            } catch (e: RuntimeException) {
                Log.w(TAG, "Could not open YouTube", e)
            }
        }
        return false
    }

    override fun bringAppToFront() {
        if (asleep()) return
        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                )
            )
        }.onFailure { Log.w(TAG, "Could not bring Simple TV forward", it) }
    }

    private companion object { const val TAG = "ExternalPlayer" }
}

/** Same preference file and key as before the controller existed, so the last channel survives upgrades. */
class PrefsLastChannelStore(context: Context) : LastChannelStore {
    private val prefs = context.getSharedPreferences("tv", Context.MODE_PRIVATE)
    override fun get(): Int? = if (prefs.contains(KEY)) prefs.getInt(KEY, 1) else null
    override fun put(number: Int) { prefs.edit().putInt(KEY, number).apply() }
    private companion object { const val KEY = "last_channel_number" }
}
