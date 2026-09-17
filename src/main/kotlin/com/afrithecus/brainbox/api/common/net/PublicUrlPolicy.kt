package com.afrithecus.brainbox.api.common.net

import java.net.InetAddress
import java.net.URI

/**
 * SSRF policy for a server-side outbound HTTP fetch: http(s) only, and the host
 * must not resolve to the host itself or the private network. It is shared by the
 * report branding-logo fetch and the content web-fetch tool so the rule lives in
 * one place.
 */
object PublicUrlPolicy {

    /** http(s) with a non-blank host. */
    fun isHttpUrl(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        return (scheme == "http" || scheme == "https") && !uri.host.isNullOrBlank()
    }

    /**
     * True when [url] is http(s) and, when the host is a literal address, that
     * address is public. A hostname is accepted here and re-checked by
     * [isPublicHost] at fetch time.
     */
    fun isAllowed(url: String): Boolean {
        val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
        val scheme = uri.scheme?.lowercase() ?: return false
        if (scheme != "http" && scheme != "https") return false
        val host = normalizeHost(uri.host) ?: return false
        val literal = literalAddressOrNull(host) ?: return true
        return !isBlockedAddress(literal)
    }

    /** The host without the brackets a URI keeps around an IPv6 literal. */
    fun normalizeHost(host: String?): String? =
        host?.trim()?.removeSurrounding("[", "]")?.takeIf { it.isNotBlank() }

    /**
     * Resolves [host] and rejects it when any resolved address is loopback,
     * site-local, link-local, multicast, any-local, carrier-grade NAT or
     * unique-local IPv6. Callers must also disable redirects so a validated host
     * cannot bounce to another address.
     */
    fun isPublicHost(host: String): Boolean {
        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrNull() ?: return false
        return addresses.isNotEmpty() && addresses.none(::isBlockedAddress)
    }

    fun isBlockedAddress(address: InetAddress): Boolean {
        if (address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress
        ) {
            return true
        }
        val bytes = address.address
        // IPv4-mapped IPv6 (::ffff:127.0.0.1): re-check the embedded IPv4 address.
        if (bytes.size == 16 && bytes.copyOfRange(0, 10).all { it == 0.toByte() } &&
            bytes[10] == 0xFF.toByte() && bytes[11] == 0xFF.toByte()
        ) {
            return isBlockedAddress(InetAddress.getByAddress(bytes.copyOfRange(12, 16)))
        }
        if (bytes.size == 16) {
            // Unique local addresses, fc00::/7.
            return (bytes[0].toInt() and 0xFE) == 0xFC
        }
        // Carrier-grade NAT, 100.64.0.0/10.
        return (bytes[0].toInt() and 0xFF) == 100 && (bytes[1].toInt() and 0xC0) == 64
    }

    private fun literalAddressOrNull(host: String): InetAddress? {
        val looksIpv4 = host.count { it == '.' } == 3 && host.all { it.isDigit() || it == '.' }
        val looksIpv6 = host.contains(':')
        if (!looksIpv4 && !looksIpv6) return null
        return runCatching { InetAddress.getByName(host) }.getOrNull()
    }
}
