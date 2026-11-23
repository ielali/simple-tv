package com.ielali.simpletv.guard

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import android.util.Log

/**
 * Turns the guard's accessibility service on or off from inside the app.
 *
 * Needs WRITE_SECURE_SETTINGS, which only adb can grant (`tools/tv-admin.sh guard-on` does it).
 * Without it every call is a harmless no-op and the caregiver enables the service once by hand.
 */
object GuardSetup {
    private const val TAG = "GuardSetup"

    fun component(context: Context): String =
        ComponentName(context, HomeGuardService::class.java).flattenToString()

    fun canSelfManage(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    fun isServiceEnabled(context: Context): Boolean {
        val cr = context.contentResolver
        val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        val on = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        return on && AccessibilityServiceList.contains(list, component(context))
    }

    /** Returns true when the secure settings now match [enabled]. */
    fun apply(context: Context, enabled: Boolean): Boolean {
        if (!canSelfManage(context)) return isServiceEnabled(context) == enabled
        return runCatching {
            val cr = context.contentResolver
            val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            val updated = if (enabled) {
                AccessibilityServiceList.add(current, component(context))
            } else {
                AccessibilityServiceList.remove(current, component(context))
            }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, updated)
            if (enabled || updated.isEmpty()) {
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, if (updated.isEmpty()) 0 else 1)
            }
            true
        }.getOrElse {
            Log.w(TAG, "Could not change accessibility settings", it)
            false
        }
    }

    /** Self-repair at app start and boot: Android can switch services off after a crash or update. */
    fun ensureEnabledIfWanted(context: Context, wanted: Boolean) {
        if (wanted && !isServiceEnabled(context)) apply(context, true)
    }
}
