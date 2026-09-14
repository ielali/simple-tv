package com.ielali.simpletv.tv

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.ielali.simpletv.R
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.player.PlaybackState
import com.ielali.simpletv.youtube.YouTubeEmbed

/** Design rules: black background, one huge banner, nothing that needs a cursor. */
private val BannerBg = Color(0xCC0B1220)
private val Accent = Color(0xFFFFC857)
private val Fg = Color(0xFFF2F4F8)

@UnstableApi
@Composable
fun PlayerScreen(state: TvUiState, vm: TvViewModel) {
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        val current = state.current
        when {
            state.channels.isEmpty() -> NoChannels(state.configUrl)
            current == null -> Unit
            current.type == SourceType.STREAM -> VideoSurface(vm)
            current.type == SourceType.YOUTUBE -> YouTubeSurface(current)
        }

        if (current?.type == SourceType.STREAM && state.playback == PlaybackState.ERROR) {
            CenterMessage(stringResource(R.string.no_signal))
        }

        AnimatedVisibility(
            visible = state.bannerVisible && state.channels.isNotEmpty(),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            ChannelBanner(current = current, pendingDigits = state.pendingDigits)
        }

        if (state.settingsVisible) SettingsOverlay(state.configUrl)
    }
}

@UnstableApi
@Composable
private fun VideoSurface(vm: TvViewModel) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            PlayerView(ctx).apply {
                player = vm.engine.player
                useController = false
                keepScreenOn = true
                resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                setShutterBackgroundColor(android.graphics.Color.BLACK)
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
    )
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun YouTubeSurface(channel: Channel) {
    val target = remember(channel.url) { YouTubeEmbed.parse(channel.url) }
    if (target == null) {
        CenterMessage(stringResource(R.string.no_signal))
        return
    }
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            WebView(ctx).apply {
                settings.javaScriptEnabled = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.domStorageEnabled = true
                settings.loadWithOverviewMode = true
                settings.useWideViewPort = true
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                setBackgroundColor(android.graphics.Color.BLACK)
                isFocusable = false
                isFocusableInTouchMode = false
                webViewClient = WebViewClient()
                keepScreenOn = true
            }
        },
        update = { web ->
            web.loadDataWithBaseURL(YouTubeEmbed.ORIGIN, YouTubeEmbed.html(target), "text/html", "utf-8", null)
        },
    )
}

@Composable
private fun ChannelBanner(current: Channel?, pendingDigits: String) {
    Row(
        modifier = Modifier
            .padding(48.dp)
            .background(BannerBg, RoundedCornerShape(16.dp))
            .padding(horizontal = 40.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val number = if (pendingDigits.isNotEmpty()) pendingDigits else current?.number?.toString() ?: "--"
        Text(
            text = number,
            color = Accent,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(32.dp))
        Text(
            text = if (pendingDigits.isNotEmpty()) "" else current?.name.orEmpty(),
            color = Fg,
            fontSize = 48.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun NoChannels(configUrl: String) {
    ConfigPrompt(
        title = stringResource(R.string.no_channels_title),
        hint = stringResource(R.string.no_channels_hint),
        configUrl = configUrl,
        footer = null,
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun SettingsOverlay(configUrl: String) {
    ConfigPrompt(
        title = stringResource(R.string.settings_title),
        hint = stringResource(R.string.settings_hint),
        configUrl = configUrl,
        footer = stringResource(R.string.settings_close),
        modifier = Modifier.fillMaxSize().background(Color(0xE60B1220)),
    )
}

/**
 * The one screen a caregiver reads off the TV: the config address in large text and a QR code that
 * opens it. Both carry the same URL so either works from across the room.
 */
@Composable
private fun ConfigPrompt(title: String, hint: String, configUrl: String, footer: String?, modifier: Modifier) {
    Row(
        modifier = modifier.padding(64.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.Center,
        ) {
            Text(title, color = Fg, fontSize = 56.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(24.dp))
            Text(hint, color = Fg, fontSize = 32.sp)
            Spacer(Modifier.height(16.dp))
            Text(configUrl, color = Accent, fontSize = 48.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            Text(stringResource(R.string.scan_hint), color = Fg.copy(alpha = 0.8f), fontSize = 28.sp)
            if (footer != null) {
                Spacer(Modifier.height(48.dp))
                Text(footer, color = Fg.copy(alpha = 0.7f), fontSize = 28.sp)
            }
        }
        Spacer(Modifier.width(48.dp))
        QrCodeView(text = configUrl, size = 300.dp)
    }
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Fg, fontSize = 48.sp, fontWeight = FontWeight.Bold)
    }
}
