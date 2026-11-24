package com.ielali.simpletv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ielali.simpletv.MainActivity
import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.guard.GuardSetup

/**
 * Brings the TV up after the box boots, and after Simple TV is updated (installing stops the app, and
 * with it the home guard), so the viewer never sees the Android launcher.
 *
 * Android 10+ blocks activity starts from the background, with exemptions. The one Simple TV relies on
 * is an enabled accessibility service (the home guard), so the guard is re-enabled first when it is
 * wanted and the app holds WRITE_SECURE_SETTINGS. See docs/DEPLOYMENT.md.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in ACTIONS) return
        val app = context.applicationContext as SimpleTvApp
        GuardSetup.ensureEnabledIfWanted(context, app.settings.current().guardEnabled)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private companion object {
        val ACTIONS = setOf(Intent.ACTION_BOOT_COMPLETED, "android.intent.action.QUICKBOOT_POWERON", Intent.ACTION_MY_PACKAGE_REPLACED)
    }
}
