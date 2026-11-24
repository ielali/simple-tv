package com.ielali.simpletv.guard

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import androidx.annotation.RequiresApi
import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.ielali.simpletv.MainActivity
import com.ielali.simpletv.SimpleTvApp
import com.ielali.simpletv.tv.TvController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Soft kiosk: keeps the viewer in Simple TV without device-owner mode or a factory reset.
 *
 *  - HOME and TV keys bring Simple TV forward instead of the Google TV home.
 *  - Assistant, colour and app-launch keys are swallowed (plus any the caregiver adds).
 *  - If the Google TV home or a sponsored app reaches the screen anyway, Simple TV is brought back,
 *    except for an app Simple TV opened itself (YouTube hand-off).
 *  - While a channel plays in the YouTube app, channel keys go to [TvController] and the channel
 *    banner is drawn over YouTube, so changing channel never passes through Simple TV's screen.
 *
 * Enabling an accessibility service also exempts the app from Android's background-activity-start
 * limits, which is what lets BootReceiver open the TV after a reboot.
 */
class HomeGuardService : AccessibilityService() {

    private val debounce = Debouncer(windowMs = 400)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var overlay: BannerOverlay? = null

    private val tv: TvController get() = (application as SimpleTvApp).tv

    override fun onServiceConnected() {
        super.onServiceConnected()
        GuardBridge.service = this
        val banner = BannerOverlay(this) {
            com.ielali.simpletv.data.TvLanguage.isRightToLeft((application as SimpleTvApp).settings.current().language)
        }.also { overlay = it }
        scope.launch {
            combine(tv.bannerVisible, tv.current, tv.tuner.pendingDigits, tv.appVisible) { visible, ch, digits, _ ->
                val overYouTube = visible && tv.isExternalFront() &&
                    HomeGuardPolicy.isActive(config(), System.currentTimeMillis())
                if (!overYouTube) null else Pair(digits.ifEmpty { ch?.number?.toString() ?: "--" }, if (digits.isEmpty()) ch?.name.orEmpty() else "")
            }.collect { text -> if (text == null) banner.hide() else banner.show(text.first, text.second) }
        }
        // The guard starts after an update or a crash with the Google TV home already in front, and no
        // window change follows to wake it: look once it has settled, and bring Simple TV back.
        checkFrontSoon("guard started")
        androidx.core.content.ContextCompat.registerReceiver(
            this, screenReceiver,
            android.content.IntentFilter().apply { addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON) },
            androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /**
     * Going into standby, Google TV puts its own home in front. Bringing Simple TV forward then would
     * turn the TV straight back on, so the guard stands down while the screen is off (and for a moment
     * after), and looks again once the TV is switched back on.
     */
    @Volatile private var screenOffAt = 0L
    private val screenReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> screenOffAt = SystemClock.uptimeMillis()
                Intent.ACTION_SCREEN_ON -> { screenOffAt = 0L; checkFrontSoon("screen on") }
            }
        }
    }

    private fun asleep(): Boolean {
        val pm = getSystemService(android.os.PowerManager::class.java)
        if (pm != null && !pm.isInteractive) return true
        return screenOffAt != 0L && SystemClock.uptimeMillis() - screenOffAt < 10_000L
    }

    private fun checkFrontSoon(why: String) {
        scope.launch {
            for (wait in longArrayOf(1_500L, 4_000L)) {
                kotlinx.coroutines.delay(wait)
                if (asleep()) return@launch
                val front = runCatching { rootInActiveWindow?.packageName?.toString() }.getOrNull()
                if (HomeGuardPolicy.onWindow(front, config(), System.currentTimeMillis()) == WindowAction.GO_HOME) {
                    goHome("$why over $front")
                    break
                }
            }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(screenReceiver) }
        if (GuardBridge.service === this) GuardBridge.service = null
        scope.cancel()
        overlay?.hide()
        super.onDestroy()
    }

    private fun config(): GuardConfig {
        val s = (application as SimpleTvApp).settings.current()
        return GuardConfig(
            enabled = s.guardEnabled,
            pausedUntil = s.guardPausedUntil,
            extraBlockedKeys = s.guardBlockedKeys.toSet(),
            allowedExternalPackages = GuardState.allowedExternalPackages,
            listKeys = s.listButtons.toSet(),
            previousKeys = s.previousButtons.toSet(),
        )
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        val pkg = event.packageName?.toString()
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            HomeGuardPolicy.onWindow(pkg, config(), System.currentTimeMillis()) == WindowAction.GO_HOME
        ) {
            goHome("window $pkg")
            return
        }
        if (pkg in HomeGuardPolicy.YOUTUBE_PACKAGES) handleYouTubeScreen(pkg!!)
    }

    private val pickerThrottle = PickerThrottle()
    private var lastPickerCheck = 0L

    /**
     * Right after Simple TV opens YouTube, a cold start can stop on "Who's watching?". Pick the first
     * account (the household's main one) so the viewer goes straight to the channel.
     */
    private fun handleYouTubeScreen(pkg: String) {
        val now = System.currentTimeMillis()
        val cfg = config()
        if (!HomeGuardPolicy.isActive(cfg, now) || pkg !in cfg.allowedExternalPackages) return
        if (now - GuardState.externalLaunchedAt > PICKER_WINDOW_MS) return
        ensurePickerSampler()
        if (now - lastPickerCheck < 500) return
        lastPickerCheck = now

        val root = rootInActiveWindow ?: return
        if (root.packageName?.toString() != pkg) return
        val infos = ArrayList<AccessibilityNodeInfo>()
        val nodes = ArrayList<ScreenNode>()
        flatten(root, infos, nodes)

        val canPressOk = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val action = AccountPickerDetector.decide(nodes, canPressOk)
        YouTubeScreenLog.recordScreen(now, nodes, picker = action != PickerAction.None)
        if (action == PickerAction.None || !pickerThrottle.tryAcquire(now)) return

        val done = when (action) {
            is PickerAction.Click -> {
                val clicked = infos[action.index].performAction(AccessibilityNodeInfo.ACTION_CLICK)
                if (clicked) "Chose \"${nodes[action.index].label.take(40)}\"" else if (canPressOk && pressOk()) "Pressed OK" else "Could not choose an account"
            }
            PickerAction.PressOk -> if (pressOk()) "Pressed OK on the first account" else "Could not press OK"
            PickerAction.None -> return
        }
        YouTubeScreenLog.recordAction(now, done)
        Log.i(TAG, "YouTube account picker: $done")
    }

    private var samplerJob: Job? = null
    private var samplerLaunch = 0L

    /**
     * YouTube on TV exposes no words, so the account screen is recognised by its look: for 30 s after
     * Simple TV opens YouTube, compare the screen with the remembered thumbnail and press OK on a match.
     */
    private fun ensurePickerSampler() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return // pressing OK needs Android 13
        val launchAt = GuardState.externalLaunchedAt
        if (launchAt == samplerLaunch && samplerJob?.isActive == true) return
        val reference = (application as SimpleTvApp).pickerReference.current().fingerprint ?: return
        samplerLaunch = launchAt
        samplerJob?.cancel()
        samplerJob = scope.launch {
            while (isActive && System.currentTimeMillis() - launchAt <= PICKER_WINDOW_MS) {
                val now = System.currentTimeMillis()
                if (HomeGuardPolicy.isActive(config(), now) && tv.isExternalFront()) {
                    val shot = captureFingerprint()
                    if (shot != null) {
                        val match = ScreenFingerprint.compare(reference, shot)
                        YouTubeScreenLog.recordMatch(now, match.toString())
                        if (match.matches && pickerThrottle.tryAcquire(now)) {
                            val done = if (pressOk()) "Recognised the account screen and pressed OK" else "Recognised the account screen but could not press OK"
                            YouTubeScreenLog.recordAction(now, done)
                            Log.i(TAG, "YouTube account picker: $done ($match)")
                        }
                    }
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    /** A 48x27 greyscale thumbnail of the screen, or null if Android refused the screenshot. */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun captureFingerprint(): Fingerprint? = suspendCancellableCoroutine { cont ->
        takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                cont.resume(runCatching { toFingerprint(result) }.getOrNull())
            }

            override fun onFailure(errorCode: Int) {
                Log.w(TAG, "Screenshot failed: $errorCode")
                cont.resume(null)
            }
        })
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun toFingerprint(result: AccessibilityService.ScreenshotResult): Fingerprint? {
        val buffer = result.hardwareBuffer
        try {
            val hardware = Bitmap.wrapHardwareBuffer(buffer, result.colorSpace) ?: return null
            val software = hardware.copy(Bitmap.Config.ARGB_8888, false)
            hardware.recycle()
            val small = Bitmap.createScaledBitmap(software, ScreenFingerprint.WIDTH, ScreenFingerprint.HEIGHT, true)
            if (small !== software) software.recycle()
            val pixels = IntArray(ScreenFingerprint.WIDTH * ScreenFingerprint.HEIGHT)
            small.getPixels(pixels, 0, ScreenFingerprint.WIDTH, 0, 0, ScreenFingerprint.WIDTH, ScreenFingerprint.HEIGHT)
            small.recycle()
            return ScreenFingerprint.fromArgb(ScreenFingerprint.WIDTH, ScreenFingerprint.HEIGHT, pixels)
        } finally {
            buffer.close()
        }
    }

    private fun pressOk(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            performGlobalAction(GLOBAL_ACTION_DPAD_CENTER)

    /** Depth-first, so the order follows the screen's reading order. Clickable rows take their children's words. */
    private fun flatten(node: AccessibilityNodeInfo, infos: MutableList<AccessibilityNodeInfo>, out: MutableList<ScreenNode>) {
        if (out.size >= MAX_NODES) return
        val own = listOfNotNull(node.text?.toString(), node.contentDescription?.toString())
            .joinToString(" ").trim()
        val label = if (own.isEmpty() && node.isClickable) subtreeText(node, depth = 3) else own
        infos += node
        out += ScreenNode(label.take(120), node.isClickable, node.isFocused)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            flatten(child, infos, out)
        }
    }

    private fun subtreeText(node: AccessibilityNodeInfo, depth: Int): String {
        if (depth == 0) return ""
        val parts = mutableListOf<String>()
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            listOfNotNull(child.text?.toString(), child.contentDescription?.toString()).forEach { if (it.isNotBlank()) parts += it }
            if (parts.size < 4) subtreeText(child, depth - 1).takeIf { it.isNotBlank() }?.let { parts += it }
        }
        return parts.joinToString(" ").trim()
    }

    override fun onKeyEvent(event: KeyEvent?): Boolean {
        if (event == null) return false
        val code = event.keyCode
        val now = System.currentTimeMillis()
        val action = if (tv.isExternalFront()) {
            HomeGuardPolicy.onKeyDuringExternal(code, config(), now, tv.tuner.pendingDigits.value.isNotEmpty())
        } else {
            HomeGuardPolicy.onKey(code, config(), now)
        }
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
            KeyAction.ROUTE -> {
                if (firstDown) tv.onRemoteKey(code)
                true
            }
            // Works over YouTube and from any other screen: Simple TV comes forward with the list open.
            KeyAction.CHANNEL_LIST -> {
                if (firstDown) tv.toggleList()
                true
            }
            KeyAction.PREVIOUS_CHANNEL -> {
                if (firstDown) tv.previousChannel()
                true
            }
        }
    }

    private fun goHome(reason: String) {
        // Never while the TV is off or going off: starting Simple TV would switch it back on.
        if (asleep()) { Log.i(TAG, "Not returning to Simple TV while the screen is off ($reason)"); return }
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

    private companion object {
        const val TAG = "HomeGuard"
        const val PICKER_WINDOW_MS = 30_000L
        const val SAMPLE_INTERVAL_MS = 1_500L
        const val MAX_NODES = 300
    }
}
