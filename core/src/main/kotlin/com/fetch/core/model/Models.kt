package com.fetch.core.model

/**
 * Which path produced a result. Recorded on every document so the router can
 * learn per-domain routing, and so debugging never has to guess.
 */
public enum class Tier {
    /** Served from the local index. No network. */
    INDEX,

    /** Plain HTTP via OkHttp. */
    HTTP,

    /** TLS-impersonated HTTP. v2 — the slot exists so adding it is registration, not surgery. */
    TLS,

    /** Real browser: WebView driven over CDP. */
    BROWSER,
}

/** Where a document originally came from. */
public enum class Source {
    /** Fetched from the web by the engine. */
    WEB,

    /** Added by the user via `add()`. */
    USER,

    /** Produced by a bounded crawl. v2. */
    CRAWL,
}

public enum class CacheMode {
    /** Index first, network on miss or staleness. The normal path. */
    DEFAULT,

    /** Never touch the network. Fails with INDEX_ONLY if absent. */
    CACHE_ONLY,

    /** Skip the index for reading, but still write the result to it. */
    NETWORK_ONLY,

    /** Refetch even if fresh. Still writes through. */
    FORCE_REFRESH,
}

/**
 * How long to wait before a page is considered ready. Only meaningful on the
 * browser tier; cheaper tiers ignore it.
 */
public enum class WaitUntil {
    LOAD,
    DOM_READY,
    NETWORK_IDLE,
    DOM_STABLE,
}

/** A URL in its three meaningful forms. Kept separate on purpose — see GOAL.md. */
public data class Urls(
    /** What the caller asked for. */
    val requested: String,
    /** Where redirects actually landed. */
    val final: String,
    /** rel=canonical, if the page declared one. */
    val canonical: String?,
)

public data class Link(
    val url: String,
    val text: String,
    val rel: String? = null,
)

/** Structured metadata lifted out of a page: OpenGraph, meta tags, JSON-LD. */
public data class PageMetadata(
    val description: String? = null,
    val author: String? = null,
    val publishedAt: Long? = null,
    val siteName: String? = null,
    val language: String? = null,
    val jsonLd: List<String> = emptyList(),
    val openGraph: Map<String, String> = emptyMap(),
)

/**
 * The engine's central value: a fetched, cleaned, indexable page.
 *
 * [markdown] is the primary content form. [text] is the plain fallback. Raw HTML
 * is deliberately NOT retained by default — it is large and rarely useful once
 * extraction has run.
 */
public data class Document(
    val urls: Urls,
    val title: String?,
    val text: String,
    val markdown: String,
    val contentHash: String,
    val fetchedAt: Long,
    val tier: Tier,
    val source: Source,
    val statusCode: Int?,
    val contentType: String?,
    val etag: String? = null,
    val lastModified: String? = null,
    val links: List<Link> = emptyList(),
    val metadata: PageMetadata = PageMetadata(),
    /** False when the page never settled and this is a partial capture. */
    val settled: Boolean = true,
)

/** One hit from a discovery source, before fusion. */
public data class SearchHit(
    val title: String,
    val url: String,
    val snippet: String,
    /** 0-based position within the source that produced it. */
    val rank: Int,
    val sourceName: String,
    val publishedAt: Long? = null,
)

/** A fused, deduplicated result. */
public data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String,
    val score: Double,
    /** Every source that returned this URL. Useful signal, and good for debugging. */
    val sources: List<String>,
    val publishedAt: Long? = null,
    /** True when this came out of the local index rather than a live source. */
    val fromIndex: Boolean = false,
)

/** A passage with provenance, so an agent can cite instead of assert. */
public data class Evidence(
    val sourceUrl: String,
    val title: String?,
    val passage: String,
    val fetchedAt: Long,
    val source: Source,
)

public data class SearchResponse(
    val query: String,
    val results: List<SearchResult>,
    val durationMs: Long,
    val fromIndex: Boolean,
    /** Sources that were queried and failed. Never fatal — recorded and moved past. */
    val failedSources: List<String> = emptyList(),
)

public data class AskResponse(
    val query: String,
    val context: String,
    val evidence: List<Evidence>,
    val durationMs: Long,
)
