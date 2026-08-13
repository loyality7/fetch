package com.fetch.core.browser

import com.fetch.core.model.WaitUntil

/**
 * The engine's view of a browser. Deliberately tiny — the engine needs three
 * things from a browser and nothing else.
 *
 * The real implementation lives in the separate `headless-android` library. This
 * interface exists so the engine never imports it directly, and so a device
 * without a working browser tier degrades instead of failing.
 *
 * Anything richer (click, type, screenshot, frames) belongs to that library's
 * API, not here. The engine has no use for it.
 */
public interface BrowserBackend : AutoCloseable {

    /** Cheap probe. False means the engine skips the browser tier entirely. */
    public suspend fun isAvailable(): Boolean

    /**
     * Open [url], wait according to [waitUntil], return the settled HTML.
     *
     * Must honour coroutine cancellation by destroying the session. Must never
     * leave a live page behind, on any path, including exceptions.
     */
    public suspend fun fetch(
        url: String,
        waitUntil: WaitUntil = WaitUntil.NETWORK_IDLE,
        blockAssets: Boolean = true,
    ): BrowserResult

    override fun close()
}

public data class BrowserResult(
    val html: String,
    val finalUrl: String,
    val statusCode: Int?,
    /** False when the settle heuristic timed out. Content is still returned. */
    val settled: Boolean,
    val bytesDownloaded: Long,
    val elapsedMs: Long,
)

/**
 * Used when no browser tier is configured or available.
 *
 * Not a failure mode — an HTTP-only engine is a legitimate, fully useful
 * configuration.
 */
public object NoBrowserBackend : BrowserBackend {
    override suspend fun isAvailable(): Boolean = false

    override suspend fun fetch(url: String, waitUntil: WaitUntil, blockAssets: Boolean): BrowserResult =
        throw UnsupportedOperationException("No browser backend configured")

    override fun close() {}
}
