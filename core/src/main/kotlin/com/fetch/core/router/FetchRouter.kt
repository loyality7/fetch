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
        val cached = index.getRaw(url)
        val document = fetchLive(url, domain, learned, cached)

        // Content hash deduplication & conditional request handling:
        // If content is unchanged (or 304 returned), reuse stored body and update TTL without full rewrite.
        val existingByHash = index.getByContentHash(document.contentHash)
        val finalDocument = if (existingByHash != null && existingByHash.urls.requested != document.urls.requested) {
            // Content matches an existing document under another URL: reuse extracted content/text/markdown
            document.copy(
                text = existingByHash.text,
                markdown = existingByHash.markdown,
                links = existingByHash.links,
                metadata = existingByHash.metadata,
            )
        } else {
            document
        }

        index.put(finalDocument, ttlFor(finalDocument))
        index.recordRouting(
            DomainRouting(domain = domain, tier = finalDocument.tier.name, lastSuccessAt = System.currentTimeMillis()),
        )
        return finalDocument
    }

    private suspend fun fetchLive(url: String, domain: String, learned: DomainRouting?, cached: Document?): Document {
        // A domain that needed the browser last time gets it immediately; paying
        // for the HTTP attempt again just to fail is the cost we are avoiding.
        val skipHttp = learned?.tier == Tier.BROWSER.name

        if (!skipHttp) {
            val headers = mutableMapOf<String, String>()
            cached?.etag?.let { headers["If-None-Match"] = it }
            cached?.lastModified?.let { headers["If-Modified-Since"] = it }

            val result = http.fetch(url, headers)

            // 304 Not Modified: return existing cached document updated with fresh fetchedAt timestamp
            if (result.statusCode == 304 && cached != null) {
                return cached.copy(
                    fetchedAt = System.currentTimeMillis(),
                    tier = Tier.INDEX,
                )
            }

            // An error status is a failure, not a hint that the page needs
            // JavaScript. Escalating here would launch a browser to render an
            // error page, and indexing the result would store it as content.
            if (result.statusCode !in SUCCESS_STATUS) {
                throw EngineException(
                    if (result.statusCode == 429) ErrorCode.RATE_LIMITED else ErrorCode.HTTP_ERROR,
                    "Server returned ${result.statusCode}",
                )
            }

            val extracted = extractor.extract(result.body, result.finalUrl, result.contentType)

            val isHtml = com.fetch.core.extract.ContentType.detect(result.contentType, result.body, url) ==
                com.fetch.core.extract.ContentType.HTML
            if (!isHtml || !needsBrowser(extracted.text, result.body)) {
                return build(url, result.finalUrl, extracted, Tier.HTTP, result.statusCode, result.contentType, result.etag, result.lastModified)
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

    /**
     * Thin output, or an obvious client-rendered shell, means HTTP was not
     * enough. Only ever asked about HTML: a short JSON response is complete,
     * not truncated, and rendering it in a browser would return the same bytes.
     */
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
        etag: String? = null,
        lastModified: String? = null,
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
        etag = etag,
        lastModified = lastModified,
        links = extracted.links,
        metadata = extracted.metadata,
    )

    private fun ttlFor(document: Document): Long {
        val url = document.urls.requested.lowercase()
        val contentType = document.contentType?.lowercase().orEmpty()

        // 1. News or dynamic feeds
        if (url.contains("/news/") || url.contains("/feed/") || url.contains("/rss") || contentType.contains("application/rss+xml") || contentType.contains("application/atom+xml")) {
            return config.index.newsTtl.inWholeMilliseconds
        }

        // 2. Documentation sites
        if (url.contains("/docs/") || url.contains("/documentation/") || url.contains("/api-docs") || url.contains("/wiki/") || url.contains("readthedocs.io") || url.contains("developer.mozilla.org")) {
            return config.index.documentationTtl.inWholeMilliseconds
        }

        // 3. Search / Discovery results
        if (url.contains("/search") || url.contains("query=") || contentType.contains("application/json")) {
            return config.index.searchTtl.inWholeMilliseconds
        }

        // 4. Default fallback
        return config.index.defaultTtl.inWholeMilliseconds
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        val SUCCESS_STATUS = 200..299

        val SPA_MARKERS = listOf(
            "<div id=\"root\"></div>",
            "<div id=\"app\"></div>",
            "__NEXT_DATA__",
            "data-reactroot",
            "ng-version",
        )
    }
}
