package com.ielali.simpletv.config

import java.io.IOException
import java.net.DatagramPacket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.NetworkInterface
import java.net.SocketTimeoutException
import kotlin.concurrent.thread

/**
 * Makes the config page reachable as http://telly.local:8080 (ADR-032). Only answers questions about
 * that one name, on the home network. The IP address keeps working whatever happens here.
 * Android-free; [SimpleTvApp][com.ielali.simpletv.SimpleTvApp] holds the Wi-Fi multicast lock.
 */
class MdnsResponder(private val lanIp: () -> String?, private val log: (String) -> Unit) {

    /** The name in use ("telly.local"), or null while there is none. */
    @Volatile var name: String? = null
        private set

    private val claim = MdnsClaim()
    private val group = InetSocketAddress(InetAddress.getByName(MdnsMessage.GROUP), MdnsMessage.PORT)

    fun start() = thread(isDaemon = true, name = "mdns") {
        while (true) {
            try {
                val ip = lanIp()
                val nic = ip?.let { NetworkInterface.getByInetAddress(InetAddress.getByName(it)) }
                if (ip != null && nic != null) serve(ip, nic)
            } catch (e: Exception) {
                log("Local name stopped: ${e.javaClass.simpleName}: ${e.message}")
            }
            name = null
            Thread.sleep(RETRY_MS)
        }
    }

    /** Until the TV's address changes. */
    private fun serve(ip: String, nic: NetworkInterface) {
        val mine = (InetAddress.getByName(ip) as Inet4Address).address.map { it.toInt() and 0xff }
        MulticastSocket(MdnsMessage.PORT).use { socket ->
            socket.networkInterface = nic
            socket.timeToLive = 255
            socket.joinGroup(group, nic)
            claim.restart()
            claimName(socket, mine)
            var checked = System.nanoTime()
            while (true) {
                val packet = receive(socket, CHECK_MS)
                val now = System.nanoTime()
                if (now - checked > CHECK_MS * 1_000_000L) {
                    checked = now
                    if (lanIp() != ip || NetworkInterface.getByInetAddress(InetAddress.getByName(ip))?.index != nic.index) return
                }
                if (packet == null) continue
                val m = MdnsMessage.parse(packet.data, packet.length) ?: continue
                val rival = claim.rival(m, mine)
                if (rival != null) {
                    if (MdnsClaim.yields(mine, rival)) { name = null; claim.taken(); claimName(socket, mine) }
                    else send(socket, MdnsMessage.answer(claim.name, mine))
                    continue
                }
                val q = MdnsMessage.questionFor(m, claim.name) ?: continue
                if (packet.port != MdnsMessage.PORT) {
                    send(socket, MdnsMessage.legacyAnswer(m, q, mine), packet.address, packet.port)
                } else {
                    val answer = MdnsMessage.answer(claim.name, mine, q.type)
                    if (q.unicast) send(socket, answer, packet.address, packet.port)
                    send(socket, answer)
                }
            }
        }
    }

    /** Asks three times whether another device has the name, then says it is this TV's. */
    private fun claimName(socket: MulticastSocket, mine: List<Int>) {
        while (true) {
            var free = true
            for (i in 1..3) {
                send(socket, MdnsMessage.query(claim.name))
                val until = System.nanoTime() + PROBE_MS * 1_000_000L
                while (free) {
                    val left = (until - System.nanoTime()) / 1_000_000L
                    if (left <= 0) break
                    val p = receive(socket, left.toInt()) ?: break
                    val m = MdnsMessage.parse(p.data, p.length) ?: continue
                    if (claim.rival(m, mine) != null) free = false
                }
                if (!free) break
            }
            if (free) break
            claim.taken()
        }
        name = claim.name
        log("Config page is also at ${claim.name}")
        repeat(2) { send(socket, MdnsMessage.answer(claim.name, mine)) }
    }

    private fun receive(socket: MulticastSocket, timeoutMs: Int): DatagramPacket? {
        socket.soTimeout = timeoutMs
        val packet = DatagramPacket(ByteArray(9000), 9000)
        return try { socket.receive(packet); packet } catch (e: SocketTimeoutException) { null }
    }

    private fun send(socket: MulticastSocket, data: ByteArray, to: InetAddress = group.address, port: Int = MdnsMessage.PORT) {
        try {
            socket.send(DatagramPacket(data, data.size, to, port))
        } catch (e: IOException) {
            // Wi-Fi may be away for a moment; the next question gets an answer.
        }
    }

    private companion object {
        const val RETRY_MS = 10_000L
        const val CHECK_MS = 5_000
        const val PROBE_MS = 250L
    }
}
