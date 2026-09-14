package com.ielali.simpletv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ielali.simpletv.MainActivity

/**
 * Brings the TV up after the box boots so the viewer never sees the Android launcher.
 *
 * Note: Android 10+ restricts launching activities from the background. On most Android TV boxes this
 * still works for the HOME app or after the user grants "Display over other apps"; the reliable route
 * for a device you set up in person is to make Simple TV the home app (see docs/DEPLOYMENT.md).
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
