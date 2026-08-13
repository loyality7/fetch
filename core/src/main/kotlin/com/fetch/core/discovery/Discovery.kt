package com.fetch.core.discovery

import com.fetch.core.config.DiscoveryConfig
import com.fetch.core.log.Log
import com.fetch.core.model.SearchResponse
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Fans out to every enabled source at once and fuses what comes back.
 *
 * Parallel with a per-source timeout, so the slowest source never gates the
 * query and a dead one costs nothing but a log line.
 */
public class Discovery(
    private val sources: List<SearchSource>,
    private val config: DiscoveryConfig,
) {

    public suspend fun search(query: String, maxResults: Int): SearchResponse = coroutineScope {
        val started = System.currentTimeMillis()
        val failed = mutableListOf<String>()

        val results = sources.map { source ->
            async {
                withTimeoutOrNull(config.perSourceTimeout) {
                    runCatching { source.search(query, maxResults) }
                        .onFailure { Log.debug("source ${source.name} failed: ${it.message}") }
                        .getOrNull()
                } ?: run {
                    synchronized(failed) { failed += source.name }
                    emptyList()
                }
            }
        }.awaitAll()

        SearchResponse(
            query = query,
            results = RankFusion.fuse(results, config.rrfK, maxResults),
            durationMs = System.currentTimeMillis() - started,
            fromIndex = false,
            failedSources = failed,
        )
    }
}
