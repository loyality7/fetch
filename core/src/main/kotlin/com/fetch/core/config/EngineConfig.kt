package com.fetch.core.config

import com.fetch.core.model.Tier
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Every limit in the engine, in one place, all overridable.
 *
 * Defaults are deliberately conservative: this runs on a phone, on a battery,
 * on someone's mobile data.
 */
public data class EngineConfig(
    val http: HttpConfig = HttpConfig(),
    val browser: BrowserConfig = BrowserConfig(),
    val index: IndexConfig = IndexConfig(),
    val discovery: DiscoveryConfig = DiscoveryConfig(),
    val security: SecurityConfig = SecurityConfig(),
    val extraction: ExtractionConfig = ExtractionConfig(),
    /** Raw protocol/wire logging. Off by default — it is noisy and can contain page content. */
    val debugLogging: Boolean = false,
)

public data class HttpConfig(
    val connectTimeout: Duration = 10.seconds,
    val readTimeout: Duration = 20.seconds,
    val totalTimeout: Duration = 30.seconds,
    val maxRedirects: Int = 10,
    val maxResponseBytes: Long = 10L * 1024 * 1024,
    val userAgent: String = DEFAULT_USER_AGENT,
    /** Per-host concurrent request cap. */
    val maxConcurrentPerHost: Int = 4,
) {
    public companion object {
        public const val DEFAULT_USER_AGENT: String =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/131.0.0.0 Mobile Safari/537.36"
    }
}

public data class BrowserConfig(
    /** Browser tier off entirely. The engine is still fully useful without it. */
    val enabled: Boolean = true,
    /** Sequential reuse. More than one live session on a phone is a memory disaster. */
    val poolSize: Int = 1,
    val navigationTimeout: Duration = 30.seconds,
    val settleTimeout: Duration = 10.seconds,
    val scriptTimeout: Duration = 5.seconds,
    val totalTaskTimeout: Duration = 60.seconds,
    /** Network idle window: no in-flight requests for this long counts as settled. */
    val networkIdleWindow: Duration = 500.milliseconds,
    /** Blocking these is the single biggest bytes-and-battery win on mobile. */
    val blockImages: Boolean = true,
    val blockFonts: Boolean = true,
    val blockMedia: Boolean = true,
    val blockStylesheets: Boolean = false,
)

public data class IndexConfig(
    val databaseName: String = "fetch.db",
    /** Beyond this, LRU eviction runs. */
    val maxSizeBytes: Long = 512L * 1024 * 1024,
    val defaultTtl: Duration = 7.days,
    val newsTtl: Duration = 1.hours,
    val searchTtl: Duration = 15.minutes,
    val documentationTtl: Duration = 30.days,
)

public data class DiscoveryConfig(
    /** Per-source budget. A slow source never gates the whole query. */
    val perSourceTimeout: Duration = 8.seconds,
    val maxResults: Int = 20,
    /** RRF constant. 60 is the value the literature settled on. */
    val rrfK: Int = 60,
    /**
     * HTML-scraped sources. Off by default: they break when markup changes and
     * invite blocking at scale. Opt-in, knowingly. See goals/not-feasible.md N9.
     */
    val enableScrapedSources: Boolean = false,
    /** Optional, user-supplied. Never required for the engine to work. */
    val braveApiKey: String? = null,
)

public data class SecurityConfig(
    /** Hosts the app explicitly permits despite the private-range block. */
    val allowedPrivateHosts: Set<String> = emptySet(),
    val allowFileScheme: Boolean = false,
    val allowContentScheme: Boolean = false,
    /** Isolated cookie jar. Never inherit the user's browser cookies. */
    val isolatedCookies: Boolean = true,
    val maxScriptOutputBytes: Long = 1L * 1024 * 1024,
)

public data class ExtractionConfig(
    /** Below this, extracted content is treated as thin and triggers escalation. */
    val minUsefulTextLength: Int = 500,
    val maxExtractedChars: Int = 500_000,
    val extractJsonLd: Boolean = true,
    val extractLinks: Boolean = true,
)

/** Escalation policy: which tiers may run, and in what order. */
public data class TierPolicy(
    val order: List<Tier> = listOf(Tier.INDEX, Tier.HTTP, Tier.BROWSER),
)
