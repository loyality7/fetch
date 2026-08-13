package com.fetch.core.router

import com.fetch.core.browser.BrowserBackend
import com.fetch.core.config.EngineConfig
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.core.extract.Extractor
import com.fetch.core.index.DomainRouting
import com.fetch.core.index.IndexStore
import com.fetch.core.log.Log
import com.fetch.core.model.CacheMode
import com.fetch.core.model.Document
import com.fetch.core.model.Source
import com.fetch.core.model.Tier
import com.fetch.core.model.Urls
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.UrlNormalizer
import java.net.URI
import java.security.MessageDigest

/**
 * Picks the cheapest path that actually works, and remembers what worked.
 *
 * Escalation is driven by what came back, not by assumptions about the domain.
 * The learned routing table then skips tiers that already failed for a host,
 * which on a phone is the difference between a browser launch and not.
 */
public class FetchRouter(
    private val config: EngineConfig,
    private val index: IndexStore,
    private val http: HttpFetcher,
    private val extractor: Extractor,
    private val browser: BrowserBackend,
) {

    public suspend fun fetch(rawUrl: String, cacheMode: CacheMode): Document {
        val url = UrlNormalizer.normalize(rawUrl)
        val domain = URI(url).host ?: throw EngineException(ErrorCode.SSRF_BLOCKED, "Missing host")

        if (cacheMode == CacheMode.DEFAULT || cacheMode == CacheMode.CACHE_ONLY) {
            index.get(url)?.let { return it }
            if (cacheMode == CacheMode.CACHE_ONLY) {
                throw EngineException(ErrorCode.INDEX_ONLY, "Not in index and network not permitted")
            }
        }

        val learned = index.routingFor(domain)
        val document = fetchLive(url, domain, learned)

        index.put(document, ttlFor(document))
        index.recordRouting(
            DomainRouting(domain = domain, tier = document.tier.name, lastSuccessAt = System.currentTimeMillis()),
        )
        return document
    }

    private suspend fun fetchLive(url: String, domain: String, learned: DomainRouting?): Document {
        // A domain that needed the browser last time gets it immediately; paying
        // for the HTTP attempt again just to fail is the cost we are avoiding.
        val skipHttp = learned?.tier == Tier.BROWSER.name

        if (!skipHttp) {
            val result = http.fetch(url)
            val extracted = extractor.extract(result.body, result.finalUrl)

            if (!needsBrowser(extracted.text, result.body)) {
                return build(url, result.finalUrl, extracted, Tier.HTTP, result.statusCode, result.contentType)
            }
            Log.debug("escalating $domain to browser tier")
        }

        if (!config.browser.enabled || !browser.isAvailable()) {
            throw EngineException(ErrorCode.JS_REQUIRED, "Page requires JavaScript and no browser is available")
        }

        val rendered = browser.fetch(url, blockAssets = true)
        val extracted = extractor.extract(rendered.html, rendered.finalUrl)
        return build(url, rendered.finalUrl, extracted, Tier.BROWSER, rendered.statusCode, "text/html")
            .copy(settled = rendered.settled)
    }

    /** Thin output, or an obvious client-rendered shell, means HTTP was not enough. */
    private fun needsBrowser(text: String, html: String): Boolean {
        if (text.length >= config.extraction.minUsefulTextLength) return false
        return SPA_MARKERS.any { html.contains(it, ignoreCase = true) } || text.length < 200
    }

    private fun build(
        requested: String,
        final: String,
        extracted: com.fetch.core.extract.Extracted,
        tier: Tier,
        statusCode: Int?,
        contentType: String?,
    ): Document = Document(
        urls = Urls(requested = requested, final = final, canonical = extracted.canonicalUrl),
        title = extracted.title,
        text = extracted.text,
        markdown = extracted.markdown,
        contentHash = sha256(extracted.text),
        fetchedAt = System.currentTimeMillis(),
        tier = tier,
        source = Source.WEB,
        statusCode = statusCode,
        contentType = contentType,
        links = extracted.links,
        metadata = extracted.metadata,
    )

    private fun ttlFor(document: Document): Long =
        config.index.defaultTtl.inWholeMilliseconds

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val SPA_MARKERS = listOf(
            "<div id=\"root\"></div>",
            "<div id=\"app\"></div>",
            "__NEXT_DATA__",
            "data-reactroot",
            "ng-version",
        )
    }
}
