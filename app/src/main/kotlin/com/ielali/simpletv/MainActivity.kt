package com.ielali.simpletv

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
import androidx.media3.common.util.UnstableApi
import com.ielali.simpletv.tv.PlayerScreen
import com.ielali.simpletv.tv.TvViewModel

@UnstableApi
class MainActivity : ComponentActivity() {

    private val vm: TvViewModel by viewModels()

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
    }

    /** Every remote key comes through here first so nothing in the view tree can steal focus. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (!vm.handles(event.keyCode)) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) vm.onKeyDown(event.keyCode)
        return true
    }

    override fun onResume() {
        super.onResume()
        vm.engine.player.playWhenReady = true
    }

    override fun onPause() {
        // Keep audio running only while visible; a set-top box going to standby should go quiet.
        vm.engine.player.playWhenReady = false
        super.onPause()
    }
}
