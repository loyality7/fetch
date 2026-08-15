package com.fetch.core.metrics

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.sdk.FetchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class OperationalMetricsTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testEngineMetricsSnapshotAndHealthIntegration() = runBlocking {
        val metrics = EngineMetrics()

        metrics.recordRequest()
        metrics.recordIndexHit()
        metrics.recordTierHttp()
        metrics.recordBytesDownloaded(4096)
        metrics.recordTiming(fetchMs = 120, extractMs = 30, searchMs = 15)
        metrics.recordSourceResult(sourceName = "hackernews", success = true, latencyMs = 250)

        val snapshot = metrics.snapshotMetrics()
        assertEquals(1L, snapshot.totalRequests)
        assertEquals(1L, snapshot.indexHits)
        assertEquals(4096L, snapshot.bytesDownloaded)
        assertEquals(120L, snapshot.avgFetchLatencyMs)
        assertEquals(30L, snapshot.avgExtractionLatencyMs)
        assertEquals(15L, snapshot.avgSearchLatencyMs)

        val sourceHealth = metrics.snapshotSourceHealth()
        assertTrue(sourceHealth.containsKey("hackernews"))
        assertEquals(250L, sourceHealth["hackernews"]?.avgLatencyMs)
        assertEquals(1L, sourceHealth["hackernews"]?.successCount)

        // Verify metrics integration via WebEngine.health()
        val engine = FetchEngine.create(context)
        engine.add(content = "Test content for metrics", title = "Metrics Test")

        val health = engine.health()
        assertNotNull(health.metrics)
        assertTrue(health.metrics.containsKey("total_requests"))
        assertTrue(health.metrics.containsKey("index_hits"))

        engine.close()
    }
}
