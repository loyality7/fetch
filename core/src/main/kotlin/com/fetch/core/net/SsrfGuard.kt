package com.fetch.core.net

import com.fetch.core.config.SecurityConfig
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.URI

/**
 * The engine fetches arbitrary URLs on behalf of an agent, so it can be pointed
 * at the host's own network. This blocks that.
 *
 * Resolution happens here rather than trusting the hostname, and the check runs
 * again after every redirect — otherwise a public host can redirect to a private
 * one, or re-resolve to it (DNS rebinding).
 */
public class SsrfGuard(private val config: SecurityConfig) {

    private val allowedSchemes = buildSet {
        add("http")
        add("https")
        if (config.allowFileScheme) add("file")
        if (config.allowContentScheme) add("content")
    }

    public fun check(url: String) {
        val uri = runCatching { URI(url) }.getOrElse {
            throw EngineException(ErrorCode.SSRF_BLOCKED, "Malformed URL")
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme !in allowedSchemes) {
            throw EngineException(ErrorCode.SSRF_BLOCKED, "Scheme not permitted: $scheme")
        }

        val host = uri.host ?: throw EngineException(ErrorCode.SSRF_BLOCKED, "Missing host")
        if (host in config.allowedPrivateHosts) return

        val addresses = runCatching { InetAddress.getAllByName(host) }.getOrElse {
            throw EngineException(ErrorCode.DNS_FAILURE, "Cannot resolve host")
        }

        addresses.forEach { address ->
            if (isPrivate(address)) {
                throw EngineException(ErrorCode.SSRF_BLOCKED, "Host resolves to a non-public address")
            }
        }
    }

    private fun isPrivate(address: InetAddress): Boolean = when {
        address.isLoopbackAddress -> true
        address.isLinkLocalAddress -> true
        address.isSiteLocalAddress -> true
        address.isAnyLocalAddress -> true
        address.isMulticastAddress -> true
        address is Inet4Address -> isPrivateV4(address)
        address is Inet6Address -> isPrivateV6(address)
        else -> false
    }

    private fun isPrivateV4(address: Inet4Address): Boolean {
        val b = address.address.map { it.toInt() and 0xFF }
        return when {
            // Carrier-grade NAT.
            b[0] == 100 && b[1] in 64..127 -> true
            // Cloud instance metadata.
            b[0] == 169 && b[1] == 254 -> true
            b[0] == 0 -> true
            else -> false
        }
    }

    // Unique local addresses (fc00::/7).
    private fun isPrivateV6(address: Inet6Address): Boolean =
        (address.address[0].toInt() and 0xFE) == 0xFC
}
