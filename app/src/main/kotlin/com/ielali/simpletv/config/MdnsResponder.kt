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

    /** The name and address last announced, until a goodbye takes them back. */
    private var announced: Pair<String, List<Int>>? = null

    fun start() = thread(isDaemon = true, name = "mdns") {
        while (true) {
            var pause = NO_NETWORK_MS
            try {
                val ip = lanIp()
                val nic = ip?.let { NetworkInterface.getByInetAddress(InetAddress.getByName(it)) }
                if (ip != null && nic != null) {
                    serve(ip, nic)
                    pause = 0 // the address changed: come straight back with the new one
                }
            } catch (e: Exception) {
                // A Wi-Fi blip after the name was up is worth a quick retry; a port that cannot be had is not.
                pause = if (name != null) BLIP_MS else FAILED_MS
                log("Local name stopped: ${e.javaClass.simpleName}: ${e.message}")
            }
            name = null
            if (pause > 0) Thread.sleep(pause)
        }
    }

    /** Until the TV's address changes. */
    private fun serve(ip: String, nic: NetworkInterface) {
        val mine = (InetAddress.getByName(ip) as Inet4Address).address.map { it.toInt() and 0xff }
        MulticastSocket(MdnsMessage.PORT).use { socket ->
            socket.networkInterface = nic
            socket.timeToLive = 255
            socket.joinGroup(group, nic)
            if (announced?.second != mine) sayGoodbye(socket)
            claim.restart()
            claimName(socket, mine)
            var checked = nowMs()
            var gaveUpAt = checked
            while (true) {
                val packet = receive(socket, CHECK_MS)
                if (nowMs() - checked >= CHECK_MS) {
                    checked = nowMs()
                    if (lanIp() != ip || NetworkInterface.getByInetAddress(InetAddress.getByName(ip))?.index != nic.index) return
                }
                val current = claim.name
                if (current == null) {
                    // Every name is taken. Look again now and then; the IP address works meanwhile.
                    if (nowMs() - gaveUpAt > GIVE_UP_MS) {
                        claim.restart()
                        claimName(socket, mine)
                        gaveUpAt = nowMs()
                    }
                    continue
                }
                val m = packet?.let { MdnsMessage.parse(it.data, it.length) } ?: continue
                val rival = claim.rival(m, mine)
                if (rival != null) {
                    if (claim.mustYield(mine, rival, nowMs())) {
                        name = null
                        sayGoodbye(socket)
                        claim.taken()
                        claimName(socket, mine)
                        gaveUpAt = nowMs()
                    } else {
                        send(socket, MdnsMessage.answer(current, mine))
                    }
                    continue
                }
                val q = MdnsMessage.questionFor(m, current) ?: continue
                if (packet.port != MdnsMessage.PORT) {
                    send(socket, MdnsMessage.legacyAnswer(m, q, mine), packet.address, packet.port)
                } else {
                    val answer = MdnsMessage.answer(current, mine, q.type)
                    if (q.unicast) send(socket, answer, packet.address, packet.port)
                    send(socket, answer)
                }
            }
        }
    }

    /** Takes the first free name, asking three times for each, and says so; none when all are taken. */
    private fun claimName(socket: MulticastSocket, mine: List<Int>) {
        while (true) {
            val wanted = claim.name
            if (wanted == null) {
                log("Every local name is taken; the config page is at the IP address only")
                return
            }
            if (isFree(socket, wanted, mine)) {
                name = wanted
                announced = wanted to mine
                log("Config page is also at $wanted")
                repeat(2) { send(socket, MdnsMessage.answer(wanted, mine)) }
                return
            }
            claim.taken()
        }
    }

    private fun isFree(socket: MulticastSocket, wanted: String, mine: List<Int>): Boolean {
        repeat(3) {
            send(socket, MdnsMessage.query(wanted))
            val until = nowMs() + PROBE_MS
            while (true) {
                val left = until - nowMs()
                if (left <= 0) break
                val p = receive(socket, left.toInt()) ?: break
                val m = MdnsMessage.parse(p.data, p.length) ?: continue
                if (claim.rival(m, mine) != null) return false
            }
        }
        return true
    }

    /** Devices forget what was announced at once, instead of keeping it until it expires. */
    private fun sayGoodbye(socket: MulticastSocket) {
        val (name, ip) = announced ?: return
        repeat(2) { send(socket, MdnsMessage.goodbye(name, ip)) }
        announced = null
    }

    private fun nowMs() = System.nanoTime() / 1_000_000L

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
        const val FAILED_MS = 10_000L
        const val BLIP_MS = 1_000L
        const val NO_NETWORK_MS = 2_000L
        const val GIVE_UP_MS = 600_000L
        const val CHECK_MS = 2_000
        const val PROBE_MS = 500L // a TV in standby answers late: 250 was missed once
    }
}
