package com.fetch.core

import com.fetch.core.browser.BrowserBackend
import com.fetch.core.browser.BrowserResult
import com.fetch.core.index.DomainRouting
import com.fetch.core.index.IndexStats
import com.fetch.core.index.IndexStore
import com.fetch.core.model.Document
import com.fetch.core.model.SearchHit
import com.fetch.core.model.SearchResult
import com.fetch.core.model.WaitUntil
import com.fetch.core.discovery.SearchSource

/** In-memory index, so router and engine tests need no device. */
class FakeIndexStore : IndexStore {

    private val documents = mutableMapOf<String, Pair<Document, Long>>()
    private val routing = mutableMapOf<String, DomainRouting>()

    var putCount: Int = 0
        private set
    var lastPutTtlMillis: Long = 0
        private set

    // Mirrors SqliteIndexStore: anything served from storage reports Tier.INDEX,
    // whatever tier originally produced it.
    override suspend fun get(url: String): Document? =
        documents[url]
            ?.takeIf { it.second > System.currentTimeMillis() }
            ?.first
            ?.copy(tier = com.fetch.core.model.Tier.INDEX)

    override suspend fun put(document: Document, ttlMillis: Long) {
        putCount++
        lastPutTtlMillis = ttlMillis
        documents[document.urls.requested] = document to (System.currentTimeMillis() + ttlMillis)
    }

    override suspend fun search(query: String, limit: Int): List<SearchResult> =
        documents.values
            .map { it.first }
            .filter { it.text.contains(query, ignoreCase = true) || it.title?.contains(query, true) == true }
            .take(limit)
            .map {
                SearchResult(
                    title = it.title.orEmpty(),
                    url = it.urls.requested,
                    snippet = it.text.take(100),
                    score = 1.0,
                    sources = listOf("index"),
                    fromIndex = true,
                )
            }

    override suspend fun routingFor(domain: String): DomainRouting? = routing[domain]

    override suspend fun recordRouting(routing: DomainRouting) {
        this.routing[routing.domain] = routing
    }

    override suspend fun stats(): IndexStats =
        IndexStats(documentCount = documents.size.toLong(), sizeBytes = documents.values.sumOf { it.first.markdown.length.toLong() })

    override suspend fun prune() {
        documents.entries.removeAll { it.value.second <= System.currentTimeMillis() }
    }

    override fun close() {
        documents.clear()
    }
}

/** Browser stub that records whether the router escalated to it. */
class FakeBrowserBackend(
    private val available: Boolean = true,
    private val html: String = "<html><body><main><p>${"Rendered by the browser tier. ".repeat(20)}</p></main></body></html>",
) : BrowserBackend {

    var fetchCount: Int = 0
        private set

    override suspend fun isAvailable(): Boolean = available

    override suspend fun fetch(url: String, waitUntil: WaitUntil, blockAssets: Boolean): BrowserResult {
        fetchCount++
        return BrowserResult(
            html = html,
            finalUrl = url,
            statusCode = 200,
            settled = true,
            bytesDownloaded = html.length.toLong(),
            elapsedMs = 1,
        )
    }

    override fun close() {}
}

/** Search source returning fixed hits, or failing, or hanging. */
class FakeSearchSource(
    override val name: String,
    private val hits: List<String> = emptyList(),
    private val failWith: Exception? = null,
    private val delayMillis: Long = 0,
) : SearchSource {

    override val isOfficial: Boolean = true

    override suspend fun search(query: String, maxResults: Int): List<SearchHit> {
        if (delayMillis > 0) kotlinx.coroutines.delay(delayMillis)
        failWith?.let { throw it }
        return hits.mapIndexed { index, url ->
            SearchHit(
                title = "Result $index from $name",
                url = url,
                snippet = "snippet $index",
                rank = index,
                sourceName = name,
            )
        }
    }
}
