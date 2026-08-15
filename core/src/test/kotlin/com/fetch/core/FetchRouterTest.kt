package com.fetch.core

import com.fetch.core.config.EngineConfig
import com.fetch.core.config.HttpConfig
import com.fetch.core.config.SecurityConfig
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.core.extract.Extractor
import com.fetch.core.model.CacheMode
import com.fetch.core.model.Tier
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.SsrfGuard
import com.fetch.core.router.FetchRouter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class FetchRouterTest {

    private lateinit var server: MockWebServer
    private lateinit var index: FakeIndexStore

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        index = FakeIndexStore()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun router(
        browser: FakeBrowserBackend = FakeBrowserBackend(),
        config: EngineConfig = EngineConfig(),
    ): FetchRouter {
        val guard = SsrfGuard(SecurityConfig(allowedPrivateHosts = setOf(server.url("/").host)))
        return FetchRouter(
            config = config,
            index = index,
            http = HttpFetcher(HttpConfig(), guard),
            extractor = Extractor(config.extraction),
            browser = browser,
        )
    }

    private fun articleHtml(words: String = "Substantial article body worth indexing. ") =
        "<html><head><title>Article</title></head><body><main><p>${words.repeat(30)}</p></main></body></html>"

    private fun spaShellHtml() =
        "<html><head><title>App</title></head><body><div id=\"root\"></div></body></html>"

    @Test
    fun `stays on http when the page has usable content`() = runTest {
        server.enqueue(MockResponse().setBody(articleHtml()))
        val browser = FakeBrowserBackend()

        val document = router(browser).fetch(server.url("/article").toString(), CacheMode.DEFAULT)

        assertEquals(Tier.HTTP, document.tier)
        assertEquals(0, browser.fetchCount)
    }

    @Test
    fun `escalates to the browser when http returns an empty shell`() = runTest {
        server.enqueue(MockResponse().setBody(spaShellHtml()))
        val browser = FakeBrowserBackend()

        val document = router(browser).fetch(server.url("/app").toString(), CacheMode.DEFAULT)

        assertEquals(Tier.BROWSER, document.tier)
        assertEquals(1, browser.fetchCount)
        assertTrue(document.text.contains("Rendered by the browser tier"))
    }

    @Test
    fun `indexes what it fetches`() = runTest {
        server.enqueue(MockResponse().setBody(articleHtml()))
        val url = server.url("/stored").toString()

        router().fetch(url, CacheMode.DEFAULT)

        assertEquals(1, index.putCount)
        assertTrue(index.get(url) != null)
    }

    @Test
    fun `serves a second request from the index without touching the network`() = runTest {
        server.enqueue(MockResponse().setBody(articleHtml()))
        val url = server.url("/twice").toString()
        val subject = router()

        subject.fetch(url, CacheMode.DEFAULT)
        val second = subject.fetch(url, CacheMode.DEFAULT)

        assertEquals(Tier.INDEX, second.tier)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `records the tier that worked for the domain`() = runTest {
        server.enqueue(MockResponse().setBody(spaShellHtml()))
        val url = server.url("/learned").toString()

        router().fetch(url, CacheMode.DEFAULT)

        assertEquals(Tier.BROWSER.name, index.routingFor(server.url("/").host)!!.tier)
    }

    @Test
    fun `skips http for a domain already known to need the browser`() = runTest {
        val host = server.url("/").host
        index.recordRouting(
            com.fetch.core.index.DomainRouting(host, Tier.BROWSER.name, System.currentTimeMillis()),
        )
        val browser = FakeBrowserBackend()

        val document = router(browser).fetch(server.url("/known").toString(), CacheMode.DEFAULT)

        assertEquals(Tier.BROWSER, document.tier)
        assertEquals(1, browser.fetchCount)
        // The saving this whole mechanism exists for: no wasted HTTP attempt.
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `cache_only fails rather than reaching the network`() = runTest {
        val error = assertThrows(EngineException::class.java) {
            runBlocking { router().fetch(server.url("/absent").toString(), CacheMode.CACHE_ONLY) }
        }

        assertEquals(ErrorCode.INDEX_ONLY, error.code)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun `force_refresh refetches even when the index has the page`() = runTest {
        server.enqueue(MockResponse().setBody(articleHtml()))
        server.enqueue(MockResponse().setBody(articleHtml("Updated body content for the page. ")))
        val url = server.url("/refresh").toString()
        val subject = router()

        subject.fetch(url, CacheMode.DEFAULT)
        val refreshed = subject.fetch(url, CacheMode.FORCE_REFRESH)

        assertEquals(Tier.HTTP, refreshed.tier)
        assertEquals(2, server.requestCount)
        assertTrue(refreshed.text.contains("Updated body content"))
    }

    @Test
    fun `reports js required when a page needs the browser and none is available`() = runTest {
        server.enqueue(MockResponse().setBody(spaShellHtml()))

        val error = assertThrows(EngineException::class.java) {
            runBlocking {
                router(FakeBrowserBackend(available = false)).fetch(server.url("/spa").toString(), CacheMode.DEFAULT)
            }
        }

        assertEquals(ErrorCode.JS_REQUIRED, error.code)
    }

    @Test
    fun `does not escalate when the browser tier is disabled by configuration`() = runTest {
        server.enqueue(MockResponse().setBody(spaShellHtml()))
        val browser = FakeBrowserBackend()
        val config = EngineConfig(browser = EngineConfig().browser.copy(enabled = false))

        val error = assertThrows(EngineException::class.java) {
            runBlocking { router(browser, config).fetch(server.url("/off").toString(), CacheMode.DEFAULT) }
        }

        assertEquals(ErrorCode.JS_REQUIRED, error.code)
        assertEquals(0, browser.fetchCount)
    }

    @Test
    fun `normalizes the url before consulting the index`() = runTest {
        server.enqueue(MockResponse().setBody(articleHtml()))
        val subject = router()
        val base = server.url("/page").toString()

        subject.fetch(base, CacheMode.DEFAULT)
        val second = subject.fetch("$base?utm_source=newsletter", CacheMode.DEFAULT)

        assertEquals(Tier.INDEX, second.tier)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `blocks a url the guard rejects before fetching`() = runTest {
        val strict = FetchRouter(
            config = EngineConfig(),
            index = index,
            http = HttpFetcher(HttpConfig(), SsrfGuard(SecurityConfig())),
            extractor = Extractor(EngineConfig().extraction),
            browser = FakeBrowserBackend(),
        )

        val error = assertThrows(EngineException::class.java) {
            runBlocking { strict.fetch("http://169.254.169.254/latest/", CacheMode.DEFAULT) }
        }

        assertEquals(ErrorCode.SSRF_BLOCKED, error.code)
    }

    @Test
    fun `an error status is a failure rather than a reason to open a browser`() = runTest {
        server.enqueue(MockResponse().setResponseCode(500).setBody("<html><body>Server error</body></html>"))
        val browser = FakeBrowserBackend()

        val error = assertThrows(EngineException::class.java) {
            runBlocking { router(browser).fetch(server.url("/broken").toString(), CacheMode.DEFAULT) }
        }

        assertEquals(ErrorCode.HTTP_ERROR, error.code)
        assertEquals(0, browser.fetchCount)
    }

    @Test
    fun `an error page is never indexed as content`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("<html><body>Not found</body></html>"))

        runCatching { router().fetch(server.url("/missing").toString(), CacheMode.DEFAULT) }

        assertEquals(0, index.putCount)
    }

    @Test
    fun `an error does not teach the router that the domain needs a browser`() = runTest {
        // One failing page must not push an entire host onto the expensive tier.
        server.enqueue(MockResponse().setResponseCode(500))
        server.enqueue(MockResponse().setBody(articleHtml()))
        val browser = FakeBrowserBackend()
        val subject = router(browser)

        runCatching { subject.fetch(server.url("/broken").toString(), CacheMode.DEFAULT) }
        val good = subject.fetch(server.url("/good").toString(), CacheMode.DEFAULT)

        assertEquals(Tier.HTTP, good.tier)
        assertEquals(0, browser.fetchCount)
    }

    @Test
    fun `rate limiting is reported as its own retryable failure`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429))

        val error = assertThrows(EngineException::class.java) {
            runBlocking { router().fetch(server.url("/limited").toString(), CacheMode.DEFAULT) }
        }

        assertEquals(ErrorCode.RATE_LIMITED, error.code)
        assertTrue(error.retryable)
    }

    @Test
    fun `ttl differs by content type and url pattern`() = runTest {
        val config = EngineConfig()
        
        server.enqueue(MockResponse().setBody(articleHtml()))
        server.enqueue(MockResponse().setBody(articleHtml()))

        val subject = router(config = config)

        val newsDocUrl = server.url("/news/breaking-story").toString()
        val docsDocUrl = server.url("/docs/api-guide").toString()

        subject.fetch(newsDocUrl, CacheMode.DEFAULT)
        val newsTtl = index.lastPutTtlMillis

        subject.fetch(docsDocUrl, CacheMode.DEFAULT)
        val docsTtl = index.lastPutTtlMillis

        // Verify stored TTL in index differs for news (1 hour) vs docs (30 days)
        assertEquals(config.index.newsTtl.inWholeMilliseconds, newsTtl)
        assertEquals(config.index.documentationTtl.inWholeMilliseconds, docsTtl)
        assertTrue(newsTtl < docsTtl)
    }
}
