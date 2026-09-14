package com.ielali.simpletv.tv

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ielali.simpletv.config.QrCode

/**
 * Draws a QR code for [text] on a white card with a quiet zone, sized for scanning from the sofa.
 * Modules are drawn as crisp rectangles, no bitmap, so it stays sharp on any TV resolution.
 */
@Composable
fun QrCodeView(text: String, size: Dp, modifier: Modifier = Modifier) {
    val matrix = remember(text) { runCatching { QrCode.encode(text) }.getOrNull() }
    val quietZone = 16.dp
    Canvas(
        modifier = modifier
            .size(size + quietZone * 2)
            .background(Color.White, RoundedCornerShape(12.dp))
            .padding(quietZone),
    ) {
        val m = matrix ?: return@Canvas
        val module = this.size.width / m.size
        for (y in 0 until m.size) {
            for (x in 0 until m.size) {
                if (m[x, y]) {
                    drawRect(
                        color = Color.Black,
                        topLeft = Offset(x * module, y * module),
                        // +0.5px overlap so anti-aliasing never leaves hairline gaps between modules
                        size = Size(module + 0.5f, module + 0.5f),
                    )
                }
            }
        }
    }
}
