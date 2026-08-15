package com.fetch.core.metrics

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance, zero-allocation lock-free operational metrics collector.
 */
public class EngineMetrics {

    // Global counters
    public val totalRequests: AtomicLong = AtomicLong(0)
    public val indexHits: AtomicLong = AtomicLong(0)
    public val indexMisses: AtomicLong = AtomicLong(0)
    public val escalationsToBrowser: AtomicLong = AtomicLong(0)
    public val bytesDownloaded: AtomicLong = AtomicLong(0)
    public val rendererCrashes: AtomicLong = AtomicLong(0)
    public val cancellations: AtomicLong = AtomicLong(0)

    // Tier counts
    public val tierHttpCount: AtomicLong = AtomicLong(0)
    public val tierBrowserCount: AtomicLong = AtomicLong(0)
    public val tierIndexCount: AtomicLong = AtomicLong(0)

    // Latencies & timings sum
    public val totalFetchTimeMs: AtomicLong = AtomicLong(0)
    public val totalExtractionTimeMs: AtomicLong = AtomicLong(0)
    public val totalSearchTimeMs: AtomicLong = AtomicLong(0)

    // Per-source operational statistics
    private val sourceStats = ConcurrentHashMap<String, SourceMetrics>()

    public fun recordRequest() {
        totalRequests.incrementAndGet()
    }

    public fun recordIndexHit() {
        indexHits.incrementAndGet()
        tierIndexCount.incrementAndGet()
    }

    public fun recordIndexMiss() {
        indexMisses.incrementAndGet()
    }

    public fun recordTierHttp() {
        tierHttpCount.incrementAndGet()
    }

    public fun recordTierBrowser() {
        tierBrowserCount.incrementAndGet()
        escalationsToBrowser.incrementAndGet()
    }

    public fun recordBytesDownloaded(bytes: Long) {
        if (bytes > 0) {
            bytesDownloaded.addAndGet(bytes)
        }
    }

    public fun recordRendererCrash() {
        rendererCrashes.incrementAndGet()
    }

    public fun recordCancellation() {
        cancellations.incrementAndGet()
    }

    public fun recordTiming(fetchMs: Long = 0, extractMs: Long = 0, searchMs: Long = 0) {
        if (fetchMs > 0) totalFetchTimeMs.addAndGet(fetchMs)
        if (extractMs > 0) totalExtractionTimeMs.addAndGet(extractMs)
        if (searchMs > 0) totalSearchTimeMs.addAndGet(searchMs)
    }

    public fun recordSourceResult(sourceName: String, success: Boolean, latencyMs: Long) {
        val stats = sourceStats.computeIfAbsent(sourceName) { SourceMetrics() }
        stats.record(success, latencyMs)
    }

    public fun snapshotSourceHealth(): Map<String, SourceHealthSnapshot> {
        return sourceStats.mapValues { (_, metrics) -> metrics.toSnapshot() }
    }

    public fun snapshotMetrics(): OperationalMetricsSnapshot {
        val reqs = totalRequests.get().coerceAtLeast(1)
        return OperationalMetricsSnapshot(
            totalRequests = totalRequests.get(),
            indexHits = indexHits.get(),
            indexMisses = indexMisses.get(),
            escalationsToBrowser = escalationsToBrowser.get(),
            bytesDownloaded = bytesDownloaded.get(),
            rendererCrashes = rendererCrashes.get(),
            cancellations = cancellations.get(),
            tierCounts = mapOf(
                "INDEX" to tierIndexCount.get(),
                "HTTP" to tierHttpCount.get(),
                "BROWSER" to tierBrowserCount.get()
            ),
            avgFetchLatencyMs = if (totalRequests.get() > 0) totalFetchTimeMs.get() / reqs else 0L,
            avgExtractionLatencyMs = if (totalRequests.get() > 0) totalExtractionTimeMs.get() / reqs else 0L,
            avgSearchLatencyMs = if (totalRequests.get() > 0) totalSearchTimeMs.get() / reqs else 0L,
        )
    }

    public class SourceMetrics {
        public val successes: AtomicLong = AtomicLong(0)
        public val failures: AtomicLong = AtomicLong(0)
        public val totalLatencyMs: AtomicLong = AtomicLong(0)
        @Volatile public var lastSuccessAt: Long? = null

        public fun record(success: Boolean, latencyMs: Long) {
            if (success) {
                successes.incrementAndGet()
                lastSuccessAt = System.currentTimeMillis()
            } else {
                failures.incrementAndGet()
            }
            totalLatencyMs.addAndGet(latencyMs)
        }

        public fun toSnapshot(): SourceHealthSnapshot {
            val total = successes.get() + failures.get()
            val avgMs = if (total > 0) totalLatencyMs.get() / total else null
            return SourceHealthSnapshot(
                enabled = true,
                lastSuccessAt = lastSuccessAt,
                recentFailures = failures.get().toInt(),
                avgLatencyMs = avgMs,
                successCount = successes.get(),
            )
        }
    }
}

public data class OperationalMetricsSnapshot(
    val totalRequests: Long,
    val indexHits: Long,
    val indexMisses: Long,
    val escalationsToBrowser: Long,
    val bytesDownloaded: Long,
    val rendererCrashes: Long,
    val cancellations: Long,
    val tierCounts: Map<String, Long>,
    val avgFetchLatencyMs: Long,
    val avgExtractionLatencyMs: Long,
    val avgSearchLatencyMs: Long,
)

public data class SourceHealthSnapshot(
    val enabled: Boolean,
    val lastSuccessAt: Long?,
    val recentFailures: Int,
    val avgLatencyMs: Long?,
    val successCount: Long,
)
