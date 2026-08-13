package com.fetch.core

import com.fetch.core.discovery.sources.JsonSearchSource
import com.fetch.core.discovery.sources.SourceCatalogue
import com.fetch.core.discovery.sources.SourceDefinition
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

/**
 * Sources are described rather than compiled in, so one implementation has to
 * cope with every response shape a published interface might use.
 */
class JsonSearchSourceTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun source(definition: SourceDefinition) = JsonSearchSource(definition)

    private fun endpoint(path: String = "/search?q={query}&n={limit}") =
        server.url("/").toString().removeSuffix("/") + path

    @Test
    fun `reads results from a flat list`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"items":[
                     {"title":"Cancelling coroutines","link":"https://q.test/1","created_date":1700000000},
                     {"title":"Structured concurrency","link":"https://q.test/2"}
                   ]}""",
            ),
        )

        val hits = source(
            SourceDefinition(
                name = "flat",
                endpoint = endpoint(),
                resultsPath = "items",
                titlePath = "title",
                urlPath = "link",
                datePath = "created_date",
            ),
        ).search("coroutine cancel", maxResults = 10)

        assertEquals(2, hits.size)
        assertEquals("Cancelling coroutines", hits.first().title)
        assertEquals("https://q.test/1", hits.first().url)
        assertEquals(0, hits.first().rank)
        assertEquals(1_700_000_000_000, hits.first().publishedAt)
    }

    @Test
    fun `reads results from a nested path`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"query":{"search":[{"title":"Web scraping","snippet":"a <b>summary</b>"}]}}"""),
        )

        val hits = source(
            SourceDefinition(
                name = "nested",
                endpoint = endpoint(),
                resultsPath = "query.search",
                titlePath = "title",
                urlTemplate = "https://wiki.test/wiki/{title}",
                snippetPath = "snippet",
            ),
        ).search("scraping", maxResults = 10)

        assertEquals("https://wiki.test/wiki/Web_scraping", hits.single().url)
        // Interfaces return markup in summaries often enough to handle here.
        assertEquals("a summary", hits.single().snippet)
    }

    @Test
    fun `prefers a supplied link over the template`() = runTest {
        server.enqueue(MockResponse().setBody("""{"hits":[{"title":"Story","url":"https://real.test/a","objectID":"7"}]}"""))

        val hits = source(SourceCatalogue.hackerNews(server.url("/").toString().removeSuffix("/"))).search("x", 10)

        assertEquals("https://real.test/a", hits.single().url)
    }

    @Test
    fun `falls back to the template when no link is supplied`() = runTest {
        server.enqueue(MockResponse().setBody("""{"hits":[{"title":"Ask HN: something","objectID":"7"}]}"""))

        val hits = source(SourceCatalogue.hackerNews(server.url("/").toString().removeSuffix("/"))).search("x", 10)

        assertEquals("https://news.ycombinator.com/item?id=7", hits.single().url)
    }

    @Test
    fun `skips results that resolve to no url at all`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[{"title":"No link here"},{"title":"Has one","link":"https://q.test/2"}]}"""))

        val hits = source(
            SourceDefinition(name = "partial", endpoint = endpoint(), resultsPath = "items", titlePath = "title", urlPath = "link"),
        ).search("x", 10)

        assertEquals(1, hits.size)
        assertEquals("https://q.test/2", hits.single().url)
    }

    @Test
    fun `honours the result limit`() = runTest {
        val items = (1..20).joinToString(",") { """{"title":"Item $it","link":"https://q.test/$it"}""" }
        server.enqueue(MockResponse().setBody("""{"items":[$items]}"""))

        val hits = source(
            SourceDefinition(name = "many", endpoint = endpoint(), resultsPath = "items", titlePath = "title", urlPath = "link"),
        ).search("x", maxResults = 3)

        assertEquals(3, hits.size)
    }

    @Test
    fun `substitutes query and limit into the endpoint`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))

        source(
            SourceDefinition(name = "params", endpoint = endpoint(), resultsPath = "items", titlePath = "title", urlPath = "link"),
        ).search("how to cancel", maxResults = 7)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("q=how+to+cancel") || request.path!!.contains("q=how%20to%20cancel"))
        assertTrue(request.path!!.contains("n=7"))
    }

    @Test
    fun `sends a user agent carrying no identifying detail`() = runTest {
        server.enqueue(MockResponse().setBody("""{"items":[]}"""))

        source(
            SourceDefinition(name = "ua", endpoint = endpoint(), resultsPath = "items", titlePath = "title", urlPath = "link"),
        ).search("x", 10)

        val agent = server.takeRequest().getHeader("User-Agent").orEmpty()
        assertEquals("fetch/0.1", agent)
    }

    @Test
    fun `fails loudly on an error status so the source can be counted as failed`() = runTest {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                source(
                    SourceDefinition(name = "limited", endpoint = endpoint(), resultsPath = "items", titlePath = "title", urlPath = "link"),
                ).search("x", 10)
            }
        }
    }

    @Test
    fun `returns nothing when the results path is absent`() = runTest {
        server.enqueue(MockResponse().setBody("""{"unexpected":"shape"}"""))

        val hits = source(
            SourceDefinition(name = "shape", endpoint = endpoint(), resultsPath = "items", titlePath = "title", urlPath = "link"),
        ).search("x", 10)

        assertTrue(hits.isEmpty())
    }

    @Test
    fun `a definition without any way to build a url is rejected on construction`() {
        assertThrows(IllegalArgumentException::class.java) {
            SourceDefinition(name = "broken", endpoint = "https://x.test", resultsPath = "items", titlePath = "title")
        }
    }

    @Test
    fun `catalogue defaults are all official and keyless`() {
        val defaults = SourceCatalogue.defaults()

        assertTrue(defaults.isNotEmpty())
        assertTrue(defaults.all { it.official })
        assertTrue(defaults.none { it.endpoint.contains("key", ignoreCase = true) })
    }

    @Test
    fun `building excludes unofficial sources unless asked for`() {
        val definitions = SourceCatalogue.defaults() + SourceDefinition(
            name = "scraped",
            endpoint = "https://x.test/?q={query}",
            resultsPath = "items",
            titlePath = "title",
            urlPath = "link",
            official = false,
        )

        assertTrue(SourceCatalogue.build(definitions).none { it.name == "scraped" })
        assertTrue(SourceCatalogue.build(definitions, includeUnofficial = true).any { it.name == "scraped" })
    }
}
