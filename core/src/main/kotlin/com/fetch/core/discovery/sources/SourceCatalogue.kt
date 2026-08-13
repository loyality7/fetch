package com.fetch.core.discovery.sources

import com.fetch.core.discovery.SearchSource
import com.fetch.core.discovery.Vertical

/**
 * Starting definitions for applications that do not want to write their own.
 *
 * These are suggestions, not the engine's furniture: an application is free to
 * pass its own list, drop any of these, or point one at a different host. The
 * engine never depends on a particular source existing.
 *
 * Every default uses a published, keyless interface belonging to the service
 * itself. None parses a search engine's result page.
 */
public object SourceCatalogue {

    /** Encyclopaedic background. MediaWiki search API. */
    public fun wikipedia(host: String = "https://en.wikipedia.org"): SourceDefinition = SourceDefinition(
        name = "wikipedia",
        endpoint = "$host/w/api.php?action=query&list=search&srsearch={query}&srlimit={limit}&format=json",
        resultsPath = "query.search",
        titlePath = "title",
        // The API returns page titles, not addresses.
        urlTemplate = "$host/wiki/{title}",
        snippetPath = "snippet",
        verticals = setOf(Vertical.GENERAL),
    )

    /** Programming questions and answers. Stack Exchange API. */
    public fun stackExchange(
        site: String = "stackoverflow",
        host: String = "https://api.stackexchange.com",
    ): SourceDefinition = SourceDefinition(
        name = "stackexchange",
        endpoint = "$host/2.3/search/advanced?order=desc&sort=relevance&q={query}" +
            "&site=$site&pagesize={limit}&filter=default",
        resultsPath = "items",
        titlePath = "title",
        urlPath = "link",
        datePath = "creation_date",
        verticals = setOf(Vertical.CODE),
    )

    /** Technical discussion. Hacker News search API. */
    public fun hackerNews(host: String = "https://hn.algolia.com"): SourceDefinition = SourceDefinition(
        name = "hackernews",
        endpoint = "$host/api/v1/search?query={query}&hitsPerPage={limit}&tags=story",
        resultsPath = "hits",
        titlePath = "title",
        urlPath = "url",
        // Text posts carry no address of their own; link to the discussion.
        urlTemplate = "https://news.ycombinator.com/item?id={objectID}",
        snippetPath = "story_text",
        datePath = "created_at_i",
        verticals = setOf(Vertical.NEWS, Vertical.CODE),
    )

    /** Software packages. crates.io registry API. */
    public fun crates(host: String = "https://crates.io"): SourceDefinition = SourceDefinition(
        name = "crates",
        endpoint = "$host/api/v1/crates?q={query}&per_page={limit}",
        resultsPath = "crates",
        titlePath = "name",
        urlTemplate = "$host/crates/{name}",
        snippetPath = "description",
        verticals = setOf(Vertical.CODE),
    )

    public fun defaults(): List<SourceDefinition> = listOf(
        wikipedia(),
        stackExchange(),
        hackerNews(),
        crates(),
    )

    /** Builds runnable sources, dropping any the configuration excludes. */
    public fun build(
        definitions: List<SourceDefinition>,
        includeUnofficial: Boolean = false,
        userAgent: String = "fetch/0.1",
    ): List<SearchSource> = definitions
        .filter { includeUnofficial || it.official }
        .map { JsonSearchSource(it, userAgent) }
}
