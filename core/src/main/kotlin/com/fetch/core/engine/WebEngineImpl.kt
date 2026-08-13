package com.fetch.core.engine

import com.fetch.core.config.EngineConfig
import com.fetch.core.discovery.Discovery
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.core.extract.Extractor
import com.fetch.core.index.IndexStore
import com.fetch.core.model.AskResponse
import com.fetch.core.model.CacheMode
import com.fetch.core.model.Document
import com.fetch.core.model.Evidence
import com.fetch.core.model.SearchResponse
import com.fetch.core.model.Source
import com.fetch.core.model.Tier
import com.fetch.core.model.Urls
import com.fetch.core.net.UrlNormalizer
import com.fetch.core.router.FetchRouter
import java.security.MessageDigest

/**
 * Wires the index, router and discovery into the public surface.
 *
 * The ordering rule the whole engine turns on: **the index answers first**. Live
 * sources are consulted only when local knowledge is thin, so repeat questions
 * cost nothing and work with the radio off.
 */
public class WebEngineImpl(
    private val config: EngineConfig,
    private val index: IndexStore,
    private val router: FetchRouter,
    private val extractor: Extractor,
    private val discovery: Discovery?,
    private val browserAvailable: suspend () -> Boolean = { false },
) : WebEngine {

    override suspend fun search(query: String, maxResults: Int, cacheMode: CacheMode): SearchResponse {
        val started = System.currentTimeMillis()
        val local = if (cacheMode == CacheMode.NETWORK_ONLY) emptyList() else index.search(query, maxResults)

        val localIsEnough = local.size >= MIN_LOCAL_RESULTS || cacheMode == CacheMode.CACHE_ONLY
        if (localIsEnough || discovery == null) {
            if (local.isEmpty() && cacheMode == CacheMode.CACHE_ONLY) {
                throw EngineException(ErrorCode.INDEX_ONLY, "Nothing indexed for this query")
            }
            return SearchResponse(
                query = query,
                results = local,
                durationMs = System.currentTimeMillis() - started,
                fromIndex = true,
            )
        }

        val live = discovery.search(query, maxResults)

        // Local hits lead: they are already fetched, already extracted, and cost
        // nothing to return. Live hits fill the remainder.
        val merged = (local + live.results.filterNot { hit -> local.any { it.url == hit.url } })
            .take(maxResults)

        return live.copy(
            results = merged,
            durationMs = System.currentTimeMillis() - started,
            fromIndex = local.isNotEmpty(),
        )
    }

    override suspend fun open(url: String, cacheMode: CacheMode): Document =
        router.fetch(url, cacheMode)

    override suspend fun extract(url: String?, content: String?): Document {
        if (url == null && content == null) {
            throw EngineException(ErrorCode.UNSUPPORTED_CONTENT, "Either a url or content is required")
        }
        if (content == null) return router.fetch(url!!, CacheMode.DEFAULT)

        val base = url ?: LOCAL_BASE
        val extracted = extractor.extract(content, base)
        return Document(
            urls = Urls(requested = base, final = base, canonical = extracted.canonicalUrl),
            title = extracted.title,
            text = extracted.text,
            markdown = extracted.markdown,
            contentHash = sha256(extracted.text),
            fetchedAt = System.currentTimeMillis(),
            tier = Tier.INDEX,
            source = Source.USER,
            statusCode = null,
            contentType = "text/html",
            links = extracted.links,
            metadata = extracted.metadata,
        )
    }

    override suspend fun find(url: String, query: String, maxPassages: Int): List<Evidence> {
        val document = router.fetch(url, CacheMode.DEFAULT)
        return passages(document, query, maxPassages)
    }

    override suspend fun add(content: String, title: String?, url: String?, source: Source): Document {
        val identity = url?.let(UrlNormalizer::normalize) ?: "$USER_SCHEME${sha256(content)}"
        val looksLikeHtml = content.contains("<html", ignoreCase = true) || content.contains("<body", ignoreCase = true)

        val document = if (looksLikeHtml) {
            val extracted = extractor.extract(content, url ?: LOCAL_BASE)
            Document(
                urls = Urls(requested = identity, final = identity, canonical = extracted.canonicalUrl),
                title = title ?: extracted.title,
                text = extracted.text,
                markdown = extracted.markdown,
                contentHash = sha256(extracted.text),
                fetchedAt = System.currentTimeMillis(),
                tier = Tier.INDEX,
                source = source,
                statusCode = null,
                contentType = "text/html",
                links = extracted.links,
                metadata = extracted.metadata,
            )
        } else {
            Document(
                urls = Urls(requested = identity, final = identity, canonical = null),
                title = title,
                text = content,
                markdown = content,
                contentHash = sha256(content),
                fetchedAt = System.currentTimeMillis(),
                tier = Tier.INDEX,
                source = source,
                statusCode = null,
                contentType = "text/plain",
            )
        }

        // User content has no expiry: the user put it there, only the user
        // removes it.
        index.put(document, ttlMillis = USER_CONTENT_TTL)
        return document
    }

    override suspend fun ask(query: String, maxSources: Int): AskResponse {
        val started = System.currentTimeMillis()
        val found = search(query, maxSources)

        val evidence = found.results.mapNotNull { result ->
            val document = index.get(result.url) ?: return@mapNotNull null
            passages(document, query, maxPassages = 2)
        }.flatten().take(maxSources * 2)

        val context = evidence.joinToString("\n\n") { "[${it.sourceUrl}] ${it.passage}" }

        return AskResponse(
            query = query,
            context = context,
            evidence = evidence,
            durationMs = System.currentTimeMillis() - started,
        )
    }

    override suspend fun health(): Health {
        val stats = index.stats()
        return Health(
            engineVersion = VERSION,
            capabilities = mapOf(
                "search" to true,
                "open" to true,
                "extract" to true,
                "find" to true,
                "add" to true,
                "ask" to true,
                "discovery" to (discovery != null),
                "browser" to browserAvailable(),
            ),
            indexedDocuments = stats.documentCount,
            indexSizeBytes = stats.sizeBytes,
            browserAvailable = browserAvailable(),
            sources = emptyMap(),
        )
    }

    override fun close() {
        index.close()
    }

    /**
     * Paragraph-level retrieval: score by how many query terms a paragraph
     * contains, longest-match first. Crude, but it cites a real passage rather
     * than the top of the page, and it needs no model.
     */
    private fun passages(document: Document, query: String, maxPassages: Int): List<Evidence> {
        val terms = query.lowercase().split(Regex("\\W+")).filter { it.length > 2 }.toSet()
        if (terms.isEmpty()) return emptyList()

        return document.markdown.split(Regex("\n{2,}"))
            .map(String::trim)
            .filter { it.length >= MIN_PASSAGE_LENGTH }
            .map { passage -> passage to terms.count { passage.contains(it, ignoreCase = true) } }
            .filter { it.second > 0 }
            .sortedByDescending { it.second }
            .take(maxPassages)
            .map { (passage, _) ->
                Evidence(
                    sourceUrl = document.urls.final,
                    title = document.title,
                    passage = passage.take(MAX_PASSAGE_LENGTH),
                    fetchedAt = document.fetchedAt,
                    source = document.source,
                )
            }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private companion object {
        const val VERSION = "0.1.0"
        const val LOCAL_BASE = "about:blank"
        const val USER_SCHEME = "user:"
        const val MIN_LOCAL_RESULTS = 3
        const val MIN_PASSAGE_LENGTH = 40
        const val MAX_PASSAGE_LENGTH = 800
        const val USER_CONTENT_TTL = Long.MAX_VALUE / 2
    }
}
