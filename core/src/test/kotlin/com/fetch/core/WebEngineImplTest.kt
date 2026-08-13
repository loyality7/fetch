package com.fetch.core

import com.fetch.core.config.EngineConfig
import com.fetch.core.config.HttpConfig
import com.fetch.core.config.SecurityConfig
import com.fetch.core.discovery.Discovery
import com.fetch.core.engine.WebEngineImpl
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.core.extract.Extractor
import com.fetch.core.model.CacheMode
import com.fetch.core.model.Source
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.SsrfGuard
import com.fetch.core.router.FetchRouter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebEngineImplTest {

    private lateinit var server: MockWebServer
    private lateinit var index: FakeIndexStore
    private val config = EngineConfig()

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

    private fun engine(discovery: Discovery? = null): WebEngineImpl {
        val guard = SsrfGuard(SecurityConfig(allowedPrivateHosts = setOf(server.url("/").host)))
        val extractor = Extractor(config.extraction)
        return WebEngineImpl(
            config = config,
            index = index,
            router = FetchRouter(config, index, HttpFetcher(HttpConfig(), guard), extractor, FakeBrowserBackend()),
            extractor = extractor,
            discovery = discovery,
        )
    }

    private fun page(body: String = "Bearer tokens are issued by the authentication service. ") =
        "<html><head><title>Guide</title></head><body><main><p>${body.repeat(20)}</p></main></body></html>"

    @Test
    fun `open fetches, extracts and indexes`() = runTest {
        server.enqueue(MockResponse().setBody(page()))

        val document = engine().open(server.url("/guide").toString())

        assertEquals("Guide", document.title)
        assertTrue(document.markdown.contains("Bearer tokens"))
        assertEquals(1, index.putCount)
    }

    @Test
    fun `search answers from the index when local knowledge is sufficient`() = runTest {
        val subject = engine()
        repeat(3) {
            subject.add("Bearer tokens are issued by the authentication service", title = "Note $it", url = "https://n.test/$it")
        }

        val response = subject.search("tokens")

        assertTrue(response.fromIndex)
        assertEquals(3, response.results.size)
    }

    @Test
    fun `search falls back to discovery when the index is thin`() = runTest {
        val discovery = Discovery(
            listOf(FakeSearchSource("alpha", hits = listOf("https://a.test/1", "https://a.test/2"))),
            config.discovery,
        )

        val response = engine(discovery).search("unseen topic")

        assertEquals(2, response.results.size)
        assertFalse(response.fromIndex)
    }

    @Test
    fun `search puts indexed results ahead of live ones`() = runTest {
        val discovery = Discovery(
            listOf(FakeSearchSource("alpha", hits = listOf("https://a.test/1"))),
            config.discovery,
        )
        val subject = engine(discovery)
        subject.add("A local note about throttling", title = "Local", url = "https://local.test/1")

        val response = subject.search("throttling")

        assertEquals("https://local.test/1", response.results.first().url)
        assertTrue(response.results.first().fromIndex)
    }

    @Test
    fun `search survives every source failing`() = runTest {
        val discovery = Discovery(
            listOf(
                FakeSearchSource("broken", failWith = IllegalStateException("markup changed")),
                FakeSearchSource("slow", delayMillis = 60_000),
            ),
            config.discovery.copy(perSourceTimeout = kotlin.time.Duration.parse("50ms")),
        )

        val response = engine(discovery).search("anything")

        assertTrue(response.results.isEmpty())
        assertTrue(response.failedSources.isNotEmpty())
    }

    @Test
    fun `cache_only search reports that nothing is indexed`() = runTest {
        val error = assertThrows(EngineException::class.java) {
            runBlocking { engine().search("never seen", cacheMode = CacheMode.CACHE_ONLY) }
        }

        assertEquals(ErrorCode.INDEX_ONLY, error.code)
    }

    @Test
    fun `add stores plain text and makes it findable`() = runTest {
        val subject = engine()

        subject.add("Rate limits are per domain and persist across restarts", title = "Limits")

        val response = subject.search("domain", cacheMode = CacheMode.CACHE_ONLY)
        assertEquals("Limits", response.results.single().title)
    }

    @Test
    fun `add extracts html rather than storing markup`() = runTest {
        val subject = engine()

        val document = subject.add(page(), title = "Imported")

        assertFalse(document.markdown.contains("<main>"))
        assertTrue(document.markdown.contains("Bearer tokens"))
        assertEquals(Source.USER, document.source)
    }

    @Test
    fun `add gives identical content the same identity`() = runTest {
        val subject = engine()

        val first = subject.add("Exactly the same body text")
        val second = subject.add("Exactly the same body text")

        assertEquals(first.urls.requested, second.urls.requested)
        assertEquals(1L, index.stats().documentCount)
    }

    @Test
    fun `extract works on supplied content without any network call`() = runTest {
        val document = engine().extract(content = page())

        assertEquals("Guide", document.title)
        assertEquals(0, server.requestCount)
        assertEquals(0, index.putCount)
    }

    @Test
    fun `extract requires either a url or content`() = runTest {
        val error = assertThrows(EngineException::class.java) {
            runBlocking { engine().extract() }
        }

        assertEquals(ErrorCode.UNSUPPORTED_CONTENT, error.code)
    }

    @Test
    fun `find returns passages that mention the query`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """
                <html><body><main>
                  <p>${"Sessions are isolated between tasks and never shared. ".repeat(4)}</p>
                  <p>${"Bearer tokens are rotated on every authentication attempt. ".repeat(4)}</p>
                </main></body></html>
                """.trimIndent(),
            ),
        )

        val evidence = engine().find(server.url("/doc").toString(), "bearer tokens")

        assertTrue(evidence.isNotEmpty())
        assertTrue(evidence.first().passage.contains("Bearer tokens"))
        assertTrue(evidence.first().sourceUrl.endsWith("/doc"))
    }

    @Test
    fun `ask assembles cited context from the index`() = runTest {
        val subject = engine()
        subject.add(
            "Cancellation propagates to the browser session and the http call alike",
            title = "Cancellation",
            url = "https://docs.test/cancel",
        )

        val response = subject.ask("cancellation")

        assertTrue(response.evidence.isNotEmpty())
        assertTrue(response.context.contains("https://docs.test/cancel"))
    }

    @Test
    fun `health reports index size and capabilities`() = runTest {
        val subject = engine()
        subject.add("Something indexed")

        val health = subject.health()

        assertEquals(1L, health.indexedDocuments)
        assertEquals(true, health.capabilities["open"])
        assertEquals(false, health.capabilities["discovery"])
        assertFalse(health.browserAvailable)
    }
}
