package com.fetch.core.engine

import com.fetch.core.model.AskResponse
import com.fetch.core.model.CacheMode
import com.fetch.core.model.Document
import com.fetch.core.model.Evidence
import com.fetch.core.model.SearchResponse
import com.fetch.core.model.Source

/**
 * The whole public surface of the engine.
 *
 * Every function suspends and is cancellable. Cancelling the calling coroutine
 * propagates all the way down: discovery, HTTP, browser session, extraction.
 * Nothing is left running.
 *
 * The caller never learns which tier, engine, or source served a result — that
 * is the point of the boundary. [Document.tier] is present for debugging and is
 * not part of the contract.
 */
public interface WebEngine : AutoCloseable {

    /**
     * Index first, then live discovery if online and the index is thin.
     * Works offline against whatever has been indexed.
     */
    public suspend fun search(
        query: String,
        maxResults: Int = 10,
        cacheMode: CacheMode = CacheMode.DEFAULT,
    ): SearchResponse

    /** Fetch a URL through the tier ladder, extract it, index it, return it. */
    public suspend fun open(
        url: String,
        cacheMode: CacheMode = CacheMode.DEFAULT,
    ): Document

    /** Extract without indexing. Accepts either a URL or raw content. */
    public suspend fun extract(url: String? = null, content: String? = null): Document

    /** Relevant passages from a single page. */
    public suspend fun find(url: String, query: String, maxPassages: Int = 5): List<Evidence>

    /** Put the user's own content into the index. Searchable identically to web content. */
    public suspend fun add(
        content: String,
        title: String? = null,
        url: String? = null,
        source: Source = Source.USER,
    ): Document

    /**
     * Retrieve and assemble context for an agent. Returns text plus citations —
     * never a generated answer. The engine does not host a model.
     */
    public suspend fun ask(query: String, maxSources: Int = 5): AskResponse

    /** Capabilities, index statistics, per-source health, version. */
    public suspend fun health(): Health

    override fun close()
}

public data class Health(
    val engineVersion: String,
    val capabilities: Map<String, Boolean>,
    val indexedDocuments: Long,
    val indexSizeBytes: Long,
    val browserAvailable: Boolean,
    val sources: Map<String, SourceHealth>,
)

public data class SourceHealth(
    val enabled: Boolean,
    val lastSuccessAt: Long?,
    val recentFailures: Int,
    val avgLatencyMs: Long?,
)
