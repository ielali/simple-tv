package com.ielali.simpletv.config

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Pure-JVM QR encoding so it is unit-testable; rendering lives in the Compose layer. */
object QrCode {

    /** A square grid of modules: `true` is a dark module. Includes no quiet zone; the renderer adds it. */
    class Matrix(val size: Int, private val bits: BooleanArray) {
        operator fun get(x: Int, y: Int): Boolean = bits[y * size + x]
    }

    fun encode(text: String): Matrix {
        val hints = mapOf(
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 0,
        )
        val bm = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val size = bm.width
        val bits = BooleanArray(size * size) { i -> bm[i % size, i / size] }
        return Matrix(size, bits)
    }
}
