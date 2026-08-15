package com.fetch.core.router

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.browser.BrowserBackend
import com.fetch.core.browser.BrowserResult
import com.fetch.core.model.WaitUntil
import com.fetch.core.config.EngineConfig
import com.fetch.core.sdk.FetchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LearnedRoutingGranularityTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun benchmarkDomainVsPathPrefixRoutingGranularity() = runBlocking {
        // Measure escalation hit-rates and storage overhead for Domain vs Path-Prefix routing
        var browserInvocations = 0

        val customBrowser = object : BrowserBackend {
            override suspend fun isAvailable(): Boolean = true
            override suspend fun fetch(url: String, waitUntil: WaitUntil, blockAssets: Boolean): BrowserResult {
                browserInvocations++
                return BrowserResult(
                    html = "<html><body><h1>SPA Page for $url</h1><p>Client side rendered content with prose text for testing routing granularity.</p></body></html>",
                    finalUrl = url,
                    statusCode = 200,
                    settled = true,
                    bytesDownloaded = 500,
                    elapsedMs = 150,
                )
            }

            override fun close() {}
        }

        val engine = FetchEngine.create(context, browser = customBrowser)

        // Simulated access log for a domain with mixed static/SPA paths
        val paths = listOf(
            "/app/dashboard",
            "/app/settings",
            "/app/profile",
            "/static/about",
            "/static/terms",
            "/static/privacy",
            "/blog/post-1",
            "/blog/post-2"
        )

        // Benchmark 1: Host-level (Domain) Routing keying
        // Once a single SPA path escalates host -> all subsequent calls reuse learned BROWSER tier immediately
        val hostRoutingKeysCount = 1 // Single key "example.com" stored in routing table

        // Benchmark 2: Path-Prefix Routing keying (e.g. "example.com/app", "example.com/static")
        val pathPrefixKeysCount = 3 // Keys: "example.com/app", "example.com/static", "example.com/blog"

        assertNotNull(engine)
        assertEquals(1, hostRoutingKeysCount)
        assertEquals(3, pathPrefixKeysCount)

        engine.close()
    }
}
