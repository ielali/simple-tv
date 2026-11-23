package com.ielali.simpletv.guard

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ScreenFingerprintTest {

    private val w = ScreenFingerprint.WIDTH
    private val h = ScreenFingerprint.HEIGHT

    private fun image(f: (x: Int, y: Int) -> Int) =
        Fingerprint(w, h, List(w * h) { i -> f(i % w, i / w).coerceIn(0, 255) })

    /** Dark screen, a lighter title band and three bright avatar blocks: like an account picker. */
    private val picker = image { x, y ->
        when {
            y in 3..5 && x in 14..33 -> 200
            y in 11..17 && (x in 8..13 || x in 21..26 || x in 34..39) -> 170
            else -> 25
        }
    }

    @Test
    fun `the same screen matches, even with small noise and brightness drift`() {
        val rnd = Random(1)
        val again = Fingerprint(w, h, picker.luma.map { (it + 6 + rnd.nextInt(-4, 5)).coerceIn(0, 255) })
        assertTrue(ScreenFingerprint.compare(picker, again).matches)
    }

    @Test
    fun `a moved focus highlight still matches`() {
        val focusMoved = image { x, y -> if (y in 18..19 && x in 21..26) 255 else picker.luma[y * w + x] }
        assertTrue(ScreenFingerprint.compare(picker, focusMoved).matches)
    }

    @Test
    fun `video frames do not match`() {
        val rnd = Random(7)
        val busy = image { _, _ -> rnd.nextInt(0, 256) }
        val darkScene = image { x, y -> 20 + (x * 3 + y * 5) % 40 }
        val gradient = image { x, _ -> x * 5 }
        listOf(busy, darkScene, gradient).forEach { assertFalse(ScreenFingerprint.compare(picker, it).matches) }
    }

    @Test
    fun `blank screens neither teach nor match`() {
        val black = image { _, _ -> 0 }
        assertTrue(ScreenFingerprint.isBlank(black))
        assertFalse(ScreenFingerprint.isBlank(picker))
        assertFalse(ScreenFingerprint.compare(picker, black).matches)
    }

    @Test
    fun `argb pixels become greyscale`() {
        val px = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        assertTrue(ScreenFingerprint.fromArgb(w, h, px).luma.all { it == 255 })
    }
}
