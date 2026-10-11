package com.example.domain.coop

import java.net.InetAddress

/**
 * Keeps the preview transport on literal RFC1918 IPv4 addresses.
 *
 * Hostnames are deliberately not resolved: accepting them would let an invite redirect a join
 * off the trusted LAN through DNS. Loopback, link-local, multicast, wildcard, and public addresses
 * are also outside the two-device Wi-Fi test boundary.
 */
object CoopLanAddressPolicy {
    fun requirePrivateIpv4(raw: String): InetAddress {
        val normalized = raw.trim()
        val parts = normalized.split('.')
        require(parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.length <= 3 && part.all(Char::isDigit)
        }) { "Use a numeric private LAN IPv4 address from the host device" }
        val octets = parts.map { part ->
            part.toIntOrNull()?.takeIf { it in 0..255 }
                ?: throw CoopProtocolException("LAN IPv4 address is invalid")
        }
        val isPrivate = octets[0] == 10 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168)
        require(isPrivate) { "Only a private LAN IPv4 address can be used for local co-op" }
        return InetAddress.getByAddress(ByteArray(4) { index -> octets[index].toByte() })
    }

    fun isPrivateIpv4(raw: String): Boolean = runCatching { requirePrivateIpv4(raw) }.isSuccess
}
