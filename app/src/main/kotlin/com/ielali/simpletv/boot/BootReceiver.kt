package com.ielali.simpletv.boot

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ielali.simpletv.MainActivity
import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.guard.GuardSetup

/**
 * Brings the TV up after the box boots so the viewer never sees the Android launcher.
 *
 * Android 10+ blocks activity starts from the background, with exemptions. The one Simple TV relies on
 * is an enabled accessibility service (the home guard), so the guard is re-enabled first when it is
 * wanted and the app holds WRITE_SECURE_SETTINGS. See docs/DEPLOYMENT.md.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != "android.intent.action.QUICKBOOT_POWERON") return
        val app = context.applicationContext as SimpleTvApp
        GuardSetup.ensureEnabledIfWanted(context, app.settings.current().guardEnabled)
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
