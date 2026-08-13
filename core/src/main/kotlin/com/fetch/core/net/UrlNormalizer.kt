package com.fetch.core.net

import java.net.URI

/**
 * Canonicalises URLs so the index does not store the same page four times.
 *
 * Conservative on purpose: a query parameter that looks decorative may be
 * load-bearing, so only known tracking parameters are dropped.
 */
public object UrlNormalizer {

    private val TRACKING_PARAMS = setOf(
        "utm_source", "utm_medium", "utm_campaign", "utm_term", "utm_content",
        "gclid", "fbclid", "msclkid", "mc_cid", "mc_eid", "igshid", "ref_src",
    )

    public fun normalize(raw: String): String {
        val uri = URI(raw.trim())
        val scheme = uri.scheme?.lowercase() ?: return raw
        val host = uri.host?.lowercase() ?: return raw

        val port = when {
            uri.port == -1 -> ""
            scheme == "http" && uri.port == 80 -> ""
            scheme == "https" && uri.port == 443 -> ""
            else -> ":${uri.port}"
        }

        val path = uri.path.orEmpty().ifEmpty { "/" }
        val query = uri.query?.let(::stripTracking)?.takeIf { it.isNotEmpty() }

        return buildString {
            append(scheme).append("://").append(host).append(port).append(path)
            if (query != null) append('?').append(query)
        }
    }

    private fun stripTracking(query: String): String =
        query.split('&')
            .filter { it.isNotEmpty() && it.substringBefore('=') !in TRACKING_PARAMS }
            .joinToString("&")
}
