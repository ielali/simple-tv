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
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.media3.ui.CaptionStyleCompat
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import com.ielali.simpletv.R
import com.ielali.simpletv.data.Channel
import com.ielali.simpletv.data.SourceType
import com.ielali.simpletv.data.playsOnSimpleTv
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
            current.playsOnSimpleTv -> VideoSurface(vm)
            current.type == SourceType.YOUTUBE ->
                if (state.youtubeInApp) ChannelCard(current, stringResource(R.string.opening_youtube)) else YouTubeSurface(current)
        }

        // While a stream tunes or has no signal, a card keeps the channel's number and name on screen.
        val streamCard = when {
            current?.playsOnSimpleTv != true -> null
            state.playback == PlaybackState.TUNING -> stringResource(R.string.tuning)
            state.playback == PlaybackState.ERROR -> stringResource(R.string.no_signal_retrying)
            else -> null
        }
        if (current != null && streamCard != null) ChannelCard(current, streamCard)
        if (current?.playsOnSimpleTv == true && state.playback == PlaybackState.REBUFFERING) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 72) }
        }

        // The card already names the channel; the corner banner still shows digits being typed.
        val cardShown = streamCard != null || (current?.type == SourceType.YOUTUBE && state.youtubeInApp)
        AnimatedVisibility(
            visible = state.channels.isNotEmpty() &&
                (state.pendingDigits.isNotEmpty() || (state.bannerVisible && !cardShown)),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            ChannelBanner(current = current, pendingDigits = state.pendingDigits, nowTitle = state.nowTitle)
        }

        if (state.settingsVisible) SettingsOverlay(state.configUrl, state.pin)
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
                subtitleView?.let { styleSubtitles(it, ctx) }
                layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            }
        },
    )
}

/**
 * Subtitles big enough to read from the sofa: the TV's own caption settings when someone set them,
 * otherwise bold white text on a dark box at about 1.6 times the usual size. The file's own fonts,
 * sizes and colours are ignored (some films ship tiny or coloured subtitles).
 */
@UnstableApi
private fun styleSubtitles(view: SubtitleView, ctx: android.content.Context) {
    view.setApplyEmbeddedStyles(false)
    view.setApplyEmbeddedFontSizes(false)
    val captions = ctx.getSystemService(android.view.accessibility.CaptioningManager::class.java)
    if (captions?.isEnabled == true) {
        view.setUserDefaultStyle()
        view.setUserDefaultTextSize()
    } else {
        view.setStyle(
            CaptionStyleCompat(
                android.graphics.Color.WHITE, 0xD9000000.toInt(), android.graphics.Color.TRANSPARENT,
                CaptionStyleCompat.EDGE_TYPE_NONE, android.graphics.Color.WHITE, android.graphics.Typeface.DEFAULT_BOLD,
            )
        )
        view.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * 1.6f)
    }
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
private fun ChannelBanner(current: Channel?, pendingDigits: String, nowTitle: String?) {
    Row(
        modifier = Modifier
            .padding(48.dp)
            .background(BannerBg, RoundedCornerShape(16.dp))
            .padding(horizontal = 40.dp, vertical = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val number = if (pendingDigits.isNotEmpty()) pendingDigits else current?.number?.takeIf { it > 0 }?.toString() ?: ""
        Text(
            text = number,
            color = Accent,
            fontSize = 96.sp,
            fontWeight = FontWeight.Black,
        )
        Spacer(Modifier.width(32.dp))
        Column {
            Text(
                text = if (pendingDigits.isNotEmpty()) "" else current?.name.orEmpty(),
                color = Fg,
                fontSize = 48.sp,
                fontWeight = FontWeight.SemiBold,
            )
            // Film and series channels: what is playing on the channel right now.
            if (pendingDigits.isEmpty() && !nowTitle.isNullOrBlank()) {
                Text(nowTitle, color = Fg.copy(alpha = 0.8f), fontSize = 32.sp)
            }
        }
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
private fun SettingsOverlay(configUrl: String, pin: String?) {
    // The page PIN is shown only here, on the TV itself, so whoever is in the room can sign in.
    val close = stringResource(R.string.settings_close)
    ConfigPrompt(
        title = stringResource(R.string.settings_title),
        hint = stringResource(R.string.settings_hint),
        configUrl = configUrl,
        footer = if (pin.isNullOrEmpty()) close else stringResource(R.string.settings_pin, pin) + "\n" + close,
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

/**
 * Full-screen card naming the channel while its picture is on the way: tuning a stream, no signal
 * (retrying), or the YouTube app opening. Large and centred so it reads from across the room.
 */
@Composable
private fun ChannelCard(channel: Channel, status: String) {
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // A "Play on TV" preview has no number (0).
        if (channel.number > 0) Text(channel.number.toString(), color = Accent, fontSize = 120.sp, fontWeight = FontWeight.Black)
        Text(channel.name, color = Fg, fontSize = 56.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(40.dp))
        Spinner(size = 64)
        Spacer(Modifier.height(24.dp))
        Text(status, color = Fg.copy(alpha = 0.75f), fontSize = 32.sp)
    }
}

@Composable
private fun Spinner(size: Int) {
    CircularProgressIndicator(color = Accent, strokeWidth = 6.dp, modifier = Modifier.size(size.dp))
}

@Composable
private fun CenterMessage(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = Fg, fontSize = 48.sp, fontWeight = FontWeight.Bold)
    }
}
