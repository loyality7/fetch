package com.fetch.core.discovery

import com.fetch.core.model.SearchHit
import com.fetch.core.model.SearchResult
import com.fetch.core.net.UrlNormalizer

/**
 * Reciprocal Rank Fusion.
 *
 *     score(url) = sum over sources of 1 / (k + rank)
 *
 * A page ranked well by several mediocre sources beats one ranked first by a
 * single source. No model, no training, no key — which is why several free
 * sources fused outperform any one of them alone.
 */
public object RankFusion {

    public fun fuse(hitsBySource: List<List<SearchHit>>, k: Int, limit: Int): List<SearchResult> {
        val scores = mutableMapOf<String, Double>()
        val best = mutableMapOf<String, SearchHit>()
        val contributors = mutableMapOf<String, MutableSet<String>>()

        hitsBySource.forEach { hits ->
            hits.forEach { hit ->
                val key = runCatching { UrlNormalizer.normalize(hit.url) }.getOrDefault(hit.url)

                scores[key] = (scores[key] ?: 0.0) + 1.0 / (k + hit.rank + 1)
                contributors.getOrPut(key) { mutableSetOf() }.add(hit.sourceName)

                // Keep the entry with the most informative snippet.
                val current = best[key]
                if (current == null || hit.snippet.length > current.snippet.length) {
                    best[key] = hit
                }
            }
        }

        return scores.entries
            .sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { (url, score) ->
                val hit = best[url] ?: return@mapNotNull null
                SearchResult(
                    title = hit.title,
                    url = url,
                    snippet = hit.snippet,
                    score = score,
                    sources = contributors[url].orEmpty().toList().sorted(),
                    publishedAt = hit.publishedAt,
                )
            }
    }
}
