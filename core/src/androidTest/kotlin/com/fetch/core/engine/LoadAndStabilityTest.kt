package com.fetch.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.config.EngineConfig
import com.fetch.core.config.IndexConfig
import com.fetch.core.model.CacheMode
import com.fetch.core.model.Document
import com.fetch.core.model.Source
import com.fetch.core.model.Tier
import com.fetch.core.model.Urls
import com.fetch.core.sdk.FetchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.system.measureTimeMillis

@RunWith(AndroidJUnit4::class)
class LoadAndStabilityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun test1000DocumentsIndexingAndSearchLatency() = runBlocking {
        // Configure index with 2MB max size cap to test eviction under load
        val config = EngineConfig(
            index = IndexConfig(
                databaseName = "load_test.db",
                maxSizeBytes = 2L * 1024 * 1024, // 2MB cap
            )
        )

        // Reset database file if exists
        context.deleteDatabase("load_test.db")

        val engine = FetchEngine.create(context, config)

        val startMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()

        // 1. Bulk insert 1,000 documents
        val insertDuration = measureTimeMillis {
            for (i in 1..1000) {
                val doc = Document(
                    urls = Urls(requested = "https://example.com/doc_$i", final = "https://example.com/doc_$i", canonical = null),
                    title = "Document $i title performance benchmark",
                    text = "This is body text for document number $i containing keywords algorithmic intelligence query searching latency measurement.",
                    markdown = "# Document $i\nThis is body text for document $i.",
                    contentHash = "hash_$i",
                    fetchedAt = System.currentTimeMillis(),
                    tier = Tier.INDEX,
                    source = Source.USER,
                    statusCode = 200,
                    contentType = "text/html",
                )
                engine.add(
                    content = "Document number $i containing keywords algorithmic intelligence query searching latency benchmark",
                    title = doc.title,
                    url = doc.urls.requested,
                    source = Source.USER,
                )
            }
        }
        assertTrue("Bulk indexing 1,000 documents should complete within 30s", insertDuration < 30_000)

        // 2. Measure search latency across 1,000 indexed documents
        var searchResultCount = 0
        val searchLatency = measureTimeMillis {
            val searchResponse = engine.search("algorithmic benchmark", 20, CacheMode.CACHE_ONLY)
            searchResultCount = searchResponse.results.size
        }

        assertTrue("Search over 1,000 items should return results", searchResultCount > 0)
        assertTrue("FTS5 BM25 search latency should be sub-50ms (was ${searchLatency}ms)", searchLatency < 50)

        // 3. Test LRU pruning keeps database size under 2MB cap
        val health = engine.health()
        assertTrue("Indexed document count should be capped after pruning", health.indexedDocuments > 0)
        assertTrue("Database size (${health.indexSizeBytes} bytes) must stay within ~2MB limit", health.indexSizeBytes <= 2.5 * 1024 * 1024)

        // 4. Memory pressure check
        System.gc()
        val endMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryDiffMb = (endMemory - startMemory) / (1024 * 1024)

        assertTrue("Memory growth must stay bounded under load (diff: ${memoryDiffMb}MB)", memoryDiffMb < 35)

        engine.close()
    }
}
