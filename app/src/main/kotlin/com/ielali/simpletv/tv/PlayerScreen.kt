package com.ielali.simpletv.tv

import android.annotation.SuppressLint
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.ui.AbsoluteAlignment
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import coil.compose.AsyncImage
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
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
import com.ielali.simpletv.data.NowNext
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
        val listOpen = state.listIndex != null
        val density = LocalDensity.current
        // Where the list's preview window is on screen; the video view moves there while the list is open.
        var previewBox by remember { mutableStateOf<Rect?>(null) }
        val inPreview = listOpen && previewBox != null
        val videoOn = if (listOpen) state.previewOn else current?.playsOnSimpleTv == true
        // One call site, so the same video view (and its picture) moves between full screen and the window.
        if (videoOn) VideoSurface(vm, if (inPreview) placedAt(previewBox!!, density).zIndex(1f) else Modifier.fillMaxSize())
        when {
            state.channels.isEmpty() -> NoChannels(state.configUrl)
            current == null || listOpen -> Unit // YouTube stops while the list is open; it reloads after
            current.playsOnSimpleTv -> Unit
            current.type == SourceType.YOUTUBE ->
                if (state.youtubeInApp) ChannelCard(current, stringResource(R.string.opening_youtube)) else YouTubeSurface(current)
        }

        // While a stream tunes or has no signal, a card keeps the channel's number and name on screen.
        val streamCard = when {
            listOpen || current?.playsOnSimpleTv != true -> null
            state.playback == PlaybackState.TUNING -> stringResource(R.string.tuning)
            state.playback == PlaybackState.ERROR -> stringResource(R.string.no_signal_retrying)
            else -> null
        }
        if (current != null && streamCard != null) ChannelCard(current, streamCard)
        if (!listOpen && current?.playsOnSimpleTv == true && state.playback == PlaybackState.REBUFFERING) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Spinner(size = 72) }
        }

        // The card already names the channel; the corner banner still shows digits being typed.
        val cardShown = streamCard != null || (current?.type == SourceType.YOUTUBE && state.youtubeInApp)
        val bannerOn = state.channels.isNotEmpty() && state.listIndex == null &&
            (state.pendingDigits.isNotEmpty() || (state.bannerVisible && !cardShown))
        AnimatedVisibility(
            visible = bannerOn,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopStart),
        ) {
            ChannelBanner(current = current, pendingDigits = state.pendingDigits, nowTitle = state.nowTitle, guide = state.guide)
        }
        // The time, with the banner: easy to lose track of in the evening.
        AnimatedVisibility(
            visible = bannerOn && state.pendingDigits.isEmpty(),
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopEnd),
        ) {
            Box(Modifier.padding(48.dp).background(BannerBg, RoundedCornerShape(16.dp)).padding(horizontal = 32.dp, vertical = 16.dp)) {
                Clock(fontSize = 48)
            }
        }

        if (state.paused && current != null && state.listIndex == null) PausedCard(current, state.nowTitle)
        if (listOpen) {
            ChannelListOverlay(state, vm, onPreviewBox = { previewBox = it })
            val box = previewBox
            if (box != null && state.previewOn && state.playback != PlaybackState.PLAYING && state.playback != PlaybackState.IDLE) {
                Box(placedAt(box, density).zIndex(2f), contentAlignment = Alignment.Center) {
                    if (state.playback == PlaybackState.ERROR) {
                        Text(stringResource(R.string.no_signal_retrying), color = Fg, fontSize = 32.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Spinner(size = 56)
                    }
                }
            }
        }
        if (state.settingsVisible) SettingsOverlay(state.configUrl, state.pin)
    }
}

@UnstableApi
@Composable
private fun VideoSurface(vm: TvViewModel, modifier: Modifier) {
    AndroidView(
        modifier = modifier,
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
private fun ChannelBanner(current: Channel?, pendingDigits: String, nowTitle: String?, guide: NowNext?) {
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
            // Live channels: the TV guide's now and next, with how far through the programme we are.
            if (pendingDigits.isEmpty() && guide?.now != null) GuideLines(guide)
        }
    }
}

/**
 * The channel list (ADR-022), laid out like a satellite receiver's: channels by number on one side; on
 * the other a preview window that plays the highlighted live channel, with what is on now and next.
 * Up/Down move, channel up/down move a page, OK watches, BACK closes and goes back to the channel that
 * was on. The video view itself is placed over [onPreviewBox]'s window by [PlayerScreen].
 */
@UnstableApi
@Composable
private fun ChannelListOverlay(state: TvUiState, vm: TvViewModel, onPreviewBox: (Rect) -> Unit) {
    val index = state.listIndex ?: return
    val channels = state.channels
    val highlighted = channels.getOrNull(index) ?: return
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color(0xF20B1220)).padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        val rows = ((maxHeight - HeaderHeight - 16.dp) / RowHeight).toInt().coerceIn(3, 9)
        SideEffect { vm.controller.listPage = rows }
        val start = ChannelListNav.windowStart(index, channels.size, rows)
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().height(HeaderHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.channel_list_title), color = Fg, fontSize = 48.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(32.dp))
                Text(
                    stringResource(R.string.channel_list_hint), color = Fg.copy(alpha = 0.75f), fontSize = 32.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(24.dp))
                Clock(fontSize = 48)
            }
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(0.5f).fillMaxHeight()) {
                    for (i in start until minOf(start + rows, channels.size)) {
                        ListRow(channels[i], highlighted = i == index, watching = channels[i].id == state.current?.id)
                    }
                }
                Spacer(Modifier.width(32.dp))
                Column(Modifier.weight(0.5f).fillMaxHeight()) {
                    Box(
                        Modifier.fillMaxWidth().aspectRatio(16f / 9f)
                            .background(Color.Black, RoundedCornerShape(12.dp))
                            .onGloballyPositioned { onPreviewBox(it.boundsInRoot()) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (!state.previewOn) PreviewCard(highlighted)
                    }
                    Spacer(Modifier.height(12.dp))
                    val title = state.nowTitle
                    val guide = state.listGuide?.takeIf { it.now != null }
                    when {
                        highlighted.id == state.current?.id && !title.isNullOrBlank() && highlighted.playlist != null ->
                            Text(title, color = Fg, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        guide != null -> ListGuide(guide, Modifier.fillMaxWidth().weight(1f))
                    }
                }
            }
        }
    }
}

/** The preview window when nothing plays in it: the channel's logo (or number) and what kind it is. */
@Composable
private fun PreviewCard(channel: Channel) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        if (!channel.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = channel.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.height(96.dp).width(200.dp),
            )
        } else if (channel.number > 0) {
            Text(channel.number.toString(), color = Accent, fontSize = 72.sp, fontWeight = FontWeight.Black)
        }
        val kind = when (channel.type) {
            SourceType.YOUTUBE -> stringResource(R.string.channel_list_kind_youtube)
            SourceType.FILMS -> stringResource(R.string.channel_list_kind_films)
            SourceType.SERIES -> stringResource(R.string.channel_list_kind_series)
            SourceType.STREAM -> null
        }
        if (kind != null) Text(kind, color = Fg.copy(alpha = 0.8f), fontSize = 32.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * Places a view at a window's position and size measured elsewhere on the screen (pixels, root
 * coordinates). Absolute left/top on purpose: in Arabic the layout runs right to left, and a mirrored
 * offset would put the picture on the wrong side of the screen.
 */
private fun BoxScope.placedAt(box: Rect, d: Density): Modifier = Modifier
    .align(AbsoluteAlignment.TopLeft)
    .absoluteOffset { IntOffset(box.left.roundToInt(), box.top.roundToInt()) }
    .size(with(d) { box.width.toDp() }, with(d) { box.height.toDp() })

private val HeaderHeight = 64.dp
private val RowHeight = 64.dp

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ListRow(channel: Channel, highlighted: Boolean, watching: Boolean) {
    val ink = if (highlighted) Color(0xFF0B1220) else Fg
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(RowHeight)
            .background(if (highlighted) Accent else Color.Transparent, RoundedCornerShape(12.dp))
            .padding(horizontal = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            if (channel.number > 0) channel.number.toString() else "",
            color = if (highlighted) ink else Accent, fontSize = 40.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.width(100.dp),
        )
        Text(
            channel.name, color = ink, fontSize = 40.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            overflow = if (highlighted) TextOverflow.Clip else TextOverflow.Ellipsis,
            // The highlighted channel's whole name scrolls by when it is too long to fit.
            modifier = Modifier.weight(1f).then(if (highlighted) Modifier.basicMarquee(initialDelayMillis = 1200) else Modifier),
        )
        // The channel on now.
        if (watching) Text("●", color = if (highlighted) ink else Accent, fontSize = 32.sp)
    }
}

/** The time in the TV's own 12- or 24-hour style, turning over on the minute. */
@Composable
private fun Clock(fontSize: Int) {
    val ctx = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(60_000L - now % 60_000L + 50L)
            now = System.currentTimeMillis()
        }
    }
    Text(fmt.format(java.util.Date(now)), color = Fg, fontSize = fontSize.sp, fontWeight = FontWeight.Bold)
}

@Composable
private fun GuideLines(guide: NowNext, barModifier: Modifier = Modifier.width(560.dp)) {
    val ctx = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    val now = guide.now ?: return
    val at = System.currentTimeMillis()
    val span = (now.stop - now.start).coerceAtLeast(1L)
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.guide_now), color = Accent, fontSize = 32.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.width(16.dp))
        Text("${fmt.format(java.util.Date(now.start))}–${fmt.format(java.util.Date(now.stop))}  ${now.title}", color = Fg, fontSize = 32.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    LinearProgressIndicator(
        progress = { ((at - now.start).toFloat() / span).coerceIn(0f, 1f) },
        modifier = barModifier.padding(vertical = 8.dp).height(8.dp),
        color = Accent,
        trackColor = Fg.copy(alpha = 0.25f),
    )
    guide.next?.let { next ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.guide_next), color = Fg.copy(alpha = 0.8f), fontSize = 32.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(16.dp))
            Text("${fmt.format(java.util.Date(next.start))}  ${next.title}", color = Fg.copy(alpha = 0.8f), fontSize = 32.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Now and next in the channel list: the programme's own name gets two lines, the times sit above it in
 * smaller type, and the description fills whatever room is left.
 */
@Composable
private fun ListGuide(guide: NowNext, modifier: Modifier) {
    val ctx = LocalContext.current
    val fmt = remember { android.text.format.DateFormat.getTimeFormat(ctx) }
    val now = guide.now ?: return
    val span = (now.stop - now.start).coerceAtLeast(1L)
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.guide_now), color = Accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
            Text("${fmt.format(java.util.Date(now.start))}–${fmt.format(java.util.Date(now.stop))}", color = Fg.copy(alpha = 0.75f), fontSize = 24.sp)
        }
        Text(now.title, color = Fg, fontSize = 30.sp, fontWeight = FontWeight.SemiBold, lineHeight = 36.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator(
            progress = { ((System.currentTimeMillis() - now.start).toFloat() / span).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp).height(6.dp),
            color = Accent,
            trackColor = Fg.copy(alpha = 0.25f),
        )
        guide.next?.let { next ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.guide_next), color = Fg.copy(alpha = 0.75f), fontSize = 24.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(12.dp))
                Text("${fmt.format(java.util.Date(next.start))}  ${next.title}", color = Fg.copy(alpha = 0.75f), fontSize = 24.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        now.desc?.let { d ->
            Spacer(Modifier.height(6.dp))
            Text(d, color = Fg.copy(alpha = 0.7f), fontSize = 22.sp, lineHeight = 28.sp, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        }
    }
}

/** OK paused a film or episode: say so in large letters, and how to carry on. */
@Composable
private fun PausedCard(channel: Channel, title: String?) {
    Box(Modifier.fillMaxSize().background(Color(0x990B1220)), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.background(BannerBg, RoundedCornerShape(24.dp)).padding(horizontal = 64.dp, vertical = 40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(stringResource(R.string.paused), color = Accent, fontSize = 72.sp, fontWeight = FontWeight.Black)
            Spacer(Modifier.height(12.dp))
            Text(title ?: channel.name, color = Fg, fontSize = 40.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.paused_hint), color = Fg.copy(alpha = 0.8f), fontSize = 32.sp)
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
        if (!channel.logoUrl.isNullOrBlank()) {
            AsyncImage(
                model = channel.logoUrl, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.height(140.dp).width(320.dp),
            )
            Spacer(Modifier.height(16.dp))
        }
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
