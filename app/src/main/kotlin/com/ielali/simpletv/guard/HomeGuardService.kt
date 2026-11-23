package com.ielali.simpletv.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ielali.simpletv.MainActivity
import com.ielali.simpletv.SimpleTvApp

/**
 * Soft kiosk: keeps the viewer in Simple TV without device-owner mode or a factory reset.
 *
 *  - HOME and TV keys bring Simple TV forward instead of the Google TV home.
 *  - Assistant, colour and app-launch keys are swallowed (plus any the caregiver adds).
 *  - If the Google TV home or a sponsored app reaches the screen anyway, Simple TV is brought back,
 *    except for an app Simple TV opened itself (YouTube hand-off).
 *
 * Enabling an accessibility service also exempts the app from Android's background-activity-start
 * limits, which is what lets BootReceiver open the TV after a reboot.
 */
class HomeGuardService : AccessibilityService() {

    private val debounce = Debouncer(windowMs = 400)

    private fun config(): GuardConfig {
        val s = (application as SimpleTvApp).settings.current()
        return GuardConfig(
            enabled = s.guardEnabled,
            pausedUntil = s.guardPausedUntil,
            extraBlockedKeys = s.guardBlockedKeys.toSet(),
            allowedExternalPackages = GuardState.allowedExternalPackages,
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString()
        if (HomeGuardPolicy.onWindow(pkg, config(), System.currentTimeMillis()) == WindowAction.GO_HOME) {
            goHome("window $pkg")
        }
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val code = event.keyCode
        val action = HomeGuardPolicy.onKey(code, config(), System.currentTimeMillis())
        val firstDown = event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0
        if (firstDown) {
            KeyLog.shared.record(
                KeyLogEntry(System.currentTimeMillis(), code, KeyEvent.keyCodeToString(code), "guard", action.name)
            )
        }
        return when (action) {
            KeyAction.PASS -> false
            // Consume DOWN, repeats and UP alike: the system acts on HOME's UP, so letting it through
            // would still open the Google TV home.
            KeyAction.SWALLOW -> true
            KeyAction.GO_HOME -> {
                if (firstDown) goHome("key ${KeyEvent.keyCodeToString(code)}")
                true
            }
        }
    }

    private fun goHome(reason: String) {
        if (!debounce.tryAcquire(SystemClock.uptimeMillis())) return
        Log.i(TAG, "Returning to Simple TV ($reason)")
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                )
            )
        }.onFailure { Log.w(TAG, "Could not bring Simple TV forward", it) }
    }

    override fun onInterrupt() = Unit

    private companion object { const val TAG = "HomeGuard" }
}
