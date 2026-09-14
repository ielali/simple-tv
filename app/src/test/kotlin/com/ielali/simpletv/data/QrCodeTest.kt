package com.ielali.simpletv.data

import com.ielali.simpletv.config.QrCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QrCodeTest {

    @Test
    fun `encodes a LAN url into a square matrix with finder patterns`() {
        val m = QrCode.encode("http://192.168.1.50:8080")
        assertTrue("version 1+ QR is at least 21 modules", m.size >= 21)
        assertEquals(1, m.size % 4) // QR sizes are 21, 25, 29, ...
        // Top-left finder pattern: 7x7 with a dark outer ring
        for (i in 0 until 7) {
            assertTrue(m[i, 0]); assertTrue(m[0, i]); assertTrue(m[i, 6]); assertTrue(m[6, i])
        }
        // and a light ring inside it
        assertTrue(!m[1, 1] && !m[5, 5] && !m[1, 5] && !m[5, 1])
    }
}
