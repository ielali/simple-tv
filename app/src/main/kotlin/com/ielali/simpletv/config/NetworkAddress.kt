package com.ielali.simpletv.config

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddress {

    /** First non-loopback IPv4 address, preferring Wi‑Fi/Ethernet interface names. */
    fun lanIp(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .sortedBy { if (it.name.startsWith("wlan") || it.name.startsWith("eth")) 0 else 1 }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }.getOrNull()

    fun configUrl(@Suppress("UNUSED_PARAMETER") context: Context, port: Int): String =
        "http://${lanIp() ?: "<tv-ip-address>"}:$port"
}
