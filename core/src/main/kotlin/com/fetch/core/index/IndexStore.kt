package com.fetch.core.index

import com.fetch.core.model.Document
import com.fetch.core.model.SearchResult

/**
 * The local index. The engine's reason to exist: everything fetched or added
 * stays here, searchable with the radio off.
 *
 * An interface rather than a concrete class only because tests want an
 * in-memory one — not in anticipation of a second production store.
 */
public interface IndexStore : AutoCloseable {

    /** Returns null on a miss or when the entry has expired. */
    public suspend fun get(url: String): Document?

    /** Returns stored document regardless of expiry (useful for conditional headers & deduplication). */
    public suspend fun getRaw(url: String): Document? = get(url)

    /** Finds existing document by contentHash if stored under any URL. */
    public suspend fun getByContentHash(hash: String): Document? = null

    public suspend fun put(document: Document, ttlMillis: Long)

    /** Keyword search over the index. Offline, always. */
    public suspend fun search(query: String, limit: Int): List<SearchResult>

    /** The tier that last worked for this domain, if any. */
    public suspend fun routingFor(domain: String): DomainRouting?

    public suspend fun recordRouting(routing: DomainRouting)

    public suspend fun stats(): IndexStats

    /** Drops expired entries, then evicts least-recently-used until under the cap. */
    public suspend fun prune()

    override fun close()
}

public data class DomainRouting(
    val domain: String,
    val tier: String,
    val lastSuccessAt: Long,
    val failureCount: Int = 0,
    val backoffUntil: Long? = null,
)

public data class IndexStats(
    val documentCount: Long,
    val sizeBytes: Long,
)
