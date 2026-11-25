package com.ielali.simpletv.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MdnsMessageTest {
    private val ip = listOf(192, 168, 0, 79)
    private val other = listOf(192, 168, 0, 80)

    private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun `a query round trips and is recognised whatever its case`() {
        val m = MdnsMessage.parse(MdnsMessage.query("Telly.LOCAL"))!!
        assertNotNull(MdnsMessage.questionFor(m, "telly.local"))
        assertNull(MdnsMessage.questionFor(m, "telly-2.local"))
        assertFalse(m.questions.single().unicast)
    }

    @Test
    fun `a second question with a compressed name and the unicast bit is read`() {
        // Question 1: x.local PTR; question 2: "telly" + pointer to "local" at offset 14, A, QU.
        val m = MdnsMessage.parse(hex("0000 0000 0002 0000 0000 0000 01 78 05 6c6f63616c 00 000c 0001 05 74656c6c79 c00e 0001 8001"))!!
        val q = MdnsMessage.questionFor(m, "telly.local")!!
        assertEquals(1, q.type)
        assertTrue(q.unicast)
    }

    @Test
    fun `the answer holds the address and is not taken for a question`() {
        for (type in listOf(1, 28, 255)) {
            val m = MdnsMessage.parse(MdnsMessage.answer("telly.local", ip, type))!!
            assertTrue(m.response)
            assertEquals(listOf(MdnsMessage.Address("telly.local", ip)), m.addresses)
            assertNull(MdnsMessage.questionFor(m, "telly.local"))
        }
    }

    @Test
    fun `a plain DNS asker gets its id and question back, and no address for IPv6`() {
        val m = MdnsMessage.Message(0x1234, false, emptyList(), emptyList())
        val a = MdnsMessage.parse(MdnsMessage.legacyAnswer(m, MdnsMessage.Question("telly.local", 1, false), ip))!!
        assertEquals(0x1234, a.id)
        assertEquals("telly.local", a.questions.single().name)
        assertEquals(ip, a.addresses.single().ip)
        val six = MdnsMessage.parse(MdnsMessage.legacyAnswer(m, MdnsMessage.Question("telly.local", 28, false), ip))!!
        assertTrue(six.addresses.isEmpty())
    }

    @Test
    fun `broken packets are ignored`() {
        assertNull(MdnsMessage.parse(ByteArray(0)))
        assertNull(MdnsMessage.parse(hex("0000 0000 0001 0000 0000 0000 05 7465")))
        // A name that points at itself, and an absurd count.
        assertNull(MdnsMessage.parse(hex("0000 0000 0001 0000 0000 0000 c00c 0001 0001")))
        assertNull(MdnsMessage.parse(hex("0000 0000 ffff ffff ffff ffff")))
        val q = MdnsMessage.query("telly.local")
        for (n in 0 until q.size) assertNull(MdnsMessage.parse(q, n))
    }

    @Test
    fun `a name another device has is passed over, one's own answer is not a rival`() {
        val claim = MdnsClaim()
        assertEquals("telly.local", claim.name)
        assertNull(claim.rival(MdnsMessage.parse(MdnsMessage.answer("telly.local", ip))!!, ip))
        assertNull(claim.rival(MdnsMessage.parse(MdnsMessage.query("telly.local"))!!, ip))
        assertEquals(other, claim.rival(MdnsMessage.parse(MdnsMessage.answer("telly.local", other))!!, ip))
        claim.taken()
        assertEquals("telly-2.local", claim.name)
        claim.restart()
        assertEquals("telly.local", claim.name)
    }

    @Test
    fun `a goodbye is a response that claims nothing`() {
        val m = MdnsMessage.parse(MdnsMessage.goodbye("telly.local", ip))!!
        assertTrue(m.response)
        assertTrue(m.addresses.isEmpty())
        assertNull(MdnsClaim().rival(m, other))
    }

    @Test
    fun `names run out, and a new network starts again`() {
        val claim = MdnsClaim()
        repeat(MdnsClaim.MAX_NAMES) { assertNotNull(claim.name); claim.taken() }
        assertNull(claim.name)
        claim.restart()
        assertEquals("telly.local", claim.name)
    }

    @Test
    fun `of two TVs with one name exactly one gives it up`() {
        assertTrue(MdnsClaim().mustYield(ip, other, 0))
        assertFalse(MdnsClaim().mustYield(other, ip, 0))
    }

    @Test
    fun `a device that will not let go gets the name, occasional conflicts do not count`() {
        val claim = MdnsClaim()
        repeat(MdnsClaim.MAX_CONFLICTS) { assertFalse(claim.mustYield(other, ip, it * 100L)) }
        assertTrue(claim.mustYield(other, ip, 1_000))
        val calm = MdnsClaim()
        repeat(50) { assertFalse(calm.mustYield(other, ip, it * 30_000L)) }
        // Conflicts over one name are not held against the next.
        val next = MdnsClaim()
        repeat(MdnsClaim.MAX_CONFLICTS) { next.mustYield(other, ip, 0) }
        next.taken()
        assertFalse(next.mustYield(other, ip, 1))
    }
}
