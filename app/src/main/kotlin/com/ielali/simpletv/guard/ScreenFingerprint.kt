package com.ielali.simpletv.guard

import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A 48x27 greyscale thumbnail of the TV screen: enough to recognise a fixed screen such as YouTube's
 * "Who's watching?", far too small to read names. YouTube on TV draws with its own engine and exposes
 * no text to accessibility services, so the guard recognises that screen by its look instead.
 */
@Serializable
data class Fingerprint(val width: Int, val height: Int, val luma: List<Int>) {
    init { require(luma.size == width * height) { "luma size ${luma.size} != $width x $height" } }
}

data class FingerprintMatch(val meanDifference: Double, val correlation: Double) {
    val matches: Boolean get() = meanDifference <= ScreenFingerprint.MAX_MEAN_DIFFERENCE &&
        correlation >= ScreenFingerprint.MIN_CORRELATION

    override fun toString() = "difference %.1f, similarity %.2f".format(meanDifference, correlation)
}

object ScreenFingerprint {
    const val WIDTH = 48
    const val HEIGHT = 27

    /** Average per-pixel difference allowed (0..255). Same screen twice: ~0-4. A different screen: 20+. */
    const val MAX_MEAN_DIFFERENCE = 12.0
    /** Pattern similarity required (Pearson correlation). Rejects dark video frames that happen to be close in brightness. */
    const val MIN_CORRELATION = 0.85
    /** Below this spread the frame is blank (black, loading); never taught, never matched. */
    const val MIN_SPREAD = 8.0

    /** ARGB pixels (already scaled to width x height) to greyscale. */
    fun fromArgb(width: Int, height: Int, argb: IntArray): Fingerprint {
        val luma = argb.map { p ->
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            (r * 299 + g * 587 + b * 114) / 1000
        }
        return Fingerprint(width, height, luma)
    }

    fun spread(f: Fingerprint): Double {
        val mean = f.luma.average()
        return sqrt(f.luma.sumOf { (it - mean) * (it - mean) } / f.luma.size)
    }

    fun isBlank(f: Fingerprint): Boolean = spread(f) < MIN_SPREAD

    fun compare(reference: Fingerprint, candidate: Fingerprint): FingerprintMatch {
        if (reference.width != candidate.width || reference.height != candidate.height || isBlank(candidate)) {
            return FingerprintMatch(255.0, 0.0)
        }
        val a = reference.luma
        val b = candidate.luma
        val n = a.size
        val mad = a.indices.sumOf { abs(a[it] - b[it]).toDouble() } / n
        val ma = a.average()
        val mb = b.average()
        var cov = 0.0; var va = 0.0; var vb = 0.0
        for (i in 0 until n) {
            val da = a[i] - ma
            val db = b[i] - mb
            cov += da * db; va += da * da; vb += db * db
        }
        val corr = if (va == 0.0 || vb == 0.0) 0.0 else cov / sqrt(va * vb)
        return FingerprintMatch(mad, corr)
    }
}
