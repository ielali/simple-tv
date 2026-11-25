package com.ielali.simpletv.config

import java.io.ByteArrayOutputStream

/** Just enough of mDNS (RFC 6762) to answer for one host name: pure, so it is tested on the JVM. */
object MdnsMessage {
    const val PORT = 5353
    const val GROUP = "224.0.0.251"

    private const val TYPE_A = 1
    private const val TYPE_NSEC = 47
    private const val TYPE_ANY = 255
    private const val CLASS_IN = 1
    private const val TOP_BIT = 0x8000
    private const val TTL_SECONDS = 120
    /** Cap for askers that are not mDNS themselves (RFC 6762 section 6.7). */
    private const val LEGACY_TTL_SECONDS = 10

    data class Question(val name: String, val type: Int, val unicast: Boolean)
    data class Address(val name: String, val ip: List<Int>)
    data class Message(val id: Int, val response: Boolean, val questions: List<Question>, val addresses: List<Address>)

    /** Names in lower case; null for anything malformed. */
    fun parse(data: ByteArray, length: Int = data.size): Message? = runCatching {
        val r = Reader(data, length)
        val id = r.u16(); val flags = r.u16()
        val counts = List(4) { r.u16() }
        if (flags and 0x7800 != 0) return null // not a plain query or response
        val questions = List(counts[0]) {
            val name = r.name(); val type = r.u16(); val cls = r.u16()
            Question(name, type, cls and TOP_BIT != 0)
        }
        val addresses = ArrayList<Address>()
        repeat(counts[1] + counts[2] + counts[3]) {
            val name = r.name(); val type = r.u16(); r.u16(); r.u16(); r.u16()
            val size = r.u16()
            val bytes = r.bytes(size)
            if (type == TYPE_A && size == 4) addresses += Address(name, bytes)
        }
        Message(id, flags and TOP_BIT != 0, questions, addresses)
    }.getOrNull()

    /** The question [m] asks about [name], if it is a query that asks one. */
    fun questionFor(m: Message, name: String): Question? =
        if (m.response) null else m.questions.firstOrNull { it.name == name }

    /** "Who is [name]?" */
    fun query(name: String): ByteArray = Writer().apply {
        header(0, 0, 1, 0, 0)
        name(name); u16(TYPE_A); u16(CLASS_IN)
    }.bytes()

    /**
     * [name] is at [ip], and has no other kind of address: without that second part a device that also
     * asks for an IPv6 address waits for it before it opens the page.
     */
    fun answer(name: String, ip: List<Int>, type: Int = TYPE_A): ByteArray = Writer().apply {
        val a = type == TYPE_A || type == TYPE_ANY
        header(0, 0x8400, 0, 1, 1)
        if (a) { address(name, ip); none(name) } else { none(name); address(name, ip) }
    }.bytes()

    /** For a plain DNS asker (its port is not 5353): its id and question come back, and it must not cache long. */
    fun legacyAnswer(m: Message, q: Question, ip: List<Int>): ByteArray = Writer().apply {
        val a = q.type == TYPE_A || q.type == TYPE_ANY
        header(m.id, 0x8400, 1, if (a) 1 else 0, 0)
        name(q.name); u16(q.type); u16(CLASS_IN)
        if (a) record(q.name, TYPE_A, CLASS_IN, LEGACY_TTL_SECONDS, ip)
    }.bytes()

    private fun Writer.address(name: String, ip: List<Int>) = record(name, TYPE_A, CLASS_IN or TOP_BIT, TTL_SECONDS, ip)

    private fun Writer.none(name: String) {
        // NSEC: next name, window 0, one byte of bitmap with only type A set.
        val data = Writer().apply { name(name); u8(0); u8(1); u8(0x40) }.bytes().map { it.toInt() and 0xff }
        record(name, TYPE_NSEC, CLASS_IN or TOP_BIT, TTL_SECONDS, data)
    }

    private class Writer {
        private val out = ByteArrayOutputStream()
        fun u8(v: Int) { out.write(v) }
        fun u16(v: Int) { u8(v shr 8 and 0xff); u8(v and 0xff) }
        fun header(id: Int, flags: Int, questions: Int, answers: Int, additional: Int) {
            u16(id); u16(flags); u16(questions); u16(answers); u16(0); u16(additional)
        }
        fun name(name: String) {
            name.split('.').forEach { l -> val b = l.toByteArray(Charsets.UTF_8); u8(b.size); out.write(b, 0, b.size) }
            u8(0)
        }
        fun record(name: String, type: Int, cls: Int, ttl: Int, data: List<Int>) {
            name(name); u16(type); u16(cls); u16(0); u16(ttl); u16(data.size)
            data.forEach { u8(it) }
        }
        fun bytes(): ByteArray = out.toByteArray()
    }

    private class Reader(private val data: ByteArray, private val length: Int) {
        private var at = 0
        private fun byteAt(i: Int): Int { require(i in 0 until length); return data[i].toInt() and 0xff }
        private fun u8(): Int = byteAt(at++)
        fun u16(): Int = u8() * 256 + u8()
        fun bytes(n: Int): List<Int> = List(n) { u8() }
        fun name(): String {
            val labels = ArrayList<String>()
            var p = at; var end = -1; var jumps = 0
            while (true) {
                val n = byteAt(p)
                if (n and 0xc0 == 0xc0) {
                    require(++jumps < 20)
                    val to = (n and 0x3f) * 256 + byteAt(p + 1)
                    if (end < 0) end = p + 2
                    p = to
                } else if (n == 0) {
                    if (end < 0) end = p + 1
                    break
                } else {
                    require(n < 64 && labels.size < 64)
                    labels += String(ByteArray(n) { byteAt(p + 1 + it).toByte() }, Charsets.UTF_8)
                    p += n + 1
                }
            }
            at = end
            return labels.joinToString(".").lowercase()
        }
    }
}

/** Which name this TV uses: telly.local, or telly-2.local and so on when another TV has it. */
class MdnsClaim(private val base: String = "telly") {
    private var number = 1
    val name: String get() = if (number == 1) "$base.local" else "$base-$number.local"

    /** Another device answered for [name] while this TV was still asking: try the next one. */
    fun taken() { number++ }

    /** A new network: start again from the plain name. */
    fun restart() { number = 1 }

    /** Is [m] another device saying it has [name]? */
    fun rival(m: MdnsMessage.Message, mine: List<Int>): List<Int>? =
        if (!m.response) null else m.addresses.firstOrNull { it.name == name && it.ip != mine }?.ip

    companion object {
        /** Two TVs that took one name at the same moment: the lower address gives it up. */
        fun yields(mine: List<Int>, other: List<Int>): Boolean {
            val i = mine.indices.firstOrNull { mine[it] != other[it] } ?: return false
            return mine[i] < other[i]
        }
    }
}
