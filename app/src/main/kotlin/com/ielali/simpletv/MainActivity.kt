package com.ielali.simpletv

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.media3.common.util.UnstableApi
import com.ielali.simpletv.tv.PlayerScreen
import com.ielali.simpletv.tv.TvViewModel
import com.ielali.simpletv.youtube.YouTubeEmbed
import kotlinx.coroutines.launch

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
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                vm.openExternal.collect { channel -> openInYouTubeApp(channel.url) }
            }
        }
    }

    /**
     * Plays a YouTube channel in the YouTube app on the box, which uses the Google account signed in
     * there. Prefers the TV build, then any handler of the URL. Falls back to the embed when neither exists.
     */
    private fun openInYouTubeApp(source: String) {
        val target = YouTubeEmbed.parse(source)
        if (target == null) {
            vm.onExternalPlayerUnavailable()
            return
        }
        val uri = Uri.parse(YouTubeEmbed.watchUrl(target))
        val candidates = listOf(
            Intent(Intent.ACTION_VIEW, uri).setPackage(YOUTUBE_TV_PACKAGE),
            Intent(Intent.ACTION_VIEW, uri).setPackage(YOUTUBE_MOBILE_PACKAGE),
            Intent(Intent.ACTION_VIEW, uri),
        )
        for (intent in candidates) {
            try {
                startActivity(intent)
                return
            } catch (e: ActivityNotFoundException) {
                // try the next candidate
            }
        }
        vm.onExternalPlayerUnavailable()
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

    private companion object {
        const val YOUTUBE_TV_PACKAGE = "com.google.android.youtube.tv"
        const val YOUTUBE_MOBILE_PACKAGE = "com.google.android.youtube"
    }

    override fun onPause() {
        // Keep audio running only while visible; a set-top box going to standby should go quiet.
        vm.engine.player.playWhenReady = false
        super.onPause()
    }
}
