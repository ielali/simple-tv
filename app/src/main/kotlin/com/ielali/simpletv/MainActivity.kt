package com.ielali.simpletv

import android.content.Context
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.media3.common.util.UnstableApi
import com.ielali.simpletv.guard.KeyLog
import com.ielali.simpletv.guard.KeyLogEntry
import com.ielali.simpletv.tv.LocaleSupport
import com.ielali.simpletv.tv.PlayerScreen
import com.ielali.simpletv.tv.TvViewModel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

@UnstableApi
class MainActivity : ComponentActivity() {

    private val vm: TvViewModel by viewModels()

    /** The TV language from the config page applies to this screen without touching the system language. */
    override fun attachBaseContext(newBase: Context) {
        val language = (newBase.applicationContext as SimpleTvApp).settings.current().language
        super.attachBaseContext(LocaleSupport.wrap(newBase, language))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            val state by vm.uiState.collectAsStateWithLifecycle()
            PlayerScreen(state = state, vm = vm)
        }
        // Language changed on the config page: rebuild the screen in the new language. The view model,
        // and with it the playing channel, survives the recreation.
        lifecycleScope.launch {
            (application as SimpleTvApp).settings.settings
                .map { it.language }.distinctUntilChanged().drop(1)
                .collect { recreate() }
        }
    }

    /** Every remote key comes through here first so nothing in the view tree can steal focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val handled = vm.handles(event.keyCode)
            KeyLog.shared.record(
                KeyLogEntry(
                    System.currentTimeMillis(), event.keyCode, KeyEvent.keyCodeToString(event.keyCode),
                    "app", if (handled) "HANDLED" else "SYSTEM",
                )
            )
        }
        if (!vm.handles(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.onKeyDown(event.keyCode)
        return true
    }

    override fun onStart() {
        super.onStart()
        vm.controller.onAppVisible(true)
    }

    override fun onStop() {
        vm.controller.onAppVisible(false)
        super.onStop()
    }

    override fun onResume() {
        super.onResume()
        vm.onScreenResumed()
    }

    override fun onPause() {
        // Keep audio running only while visible; a set-top box going to standby should go quiet.
        vm.engine.player.playWhenReady = false
        super.onPause()
    }
}
