package com.fetch.core.discovery

import com.fetch.core.model.SearchHit

/**
 * One place to ask for results.
 *
 * Sources are disposable by design: any of them can die, change its markup, or
 * start rate-limiting, and the engine must keep working. Never make one
 * load-bearing.
 */
public interface SearchSource {

    /** Stable identifier, reported in results and health. */
    public val name: String

    /**
     * False for sources that scrape HTML rather than use a published endpoint.
     * These ship disabled and are opted into knowingly.
     */
    public val isOfficial: Boolean

    /** Hint for intent routing. Empty means general-purpose. */
    public val verticals: Set<Vertical>
        get() = emptySet()

    public suspend fun search(query: String, maxResults: Int): List<SearchHit>
}

public enum class Vertical {
    GENERAL,
    CODE,
    DOCS,
    PAPERS,
    NEWS,
}
