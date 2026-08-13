package com.fetch.core.index

import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.config.IndexConfig
import com.fetch.core.model.Document
import com.fetch.core.model.Link
import com.fetch.core.model.PageMetadata
import com.fetch.core.model.Source
import com.fetch.core.model.Tier
import com.fetch.core.model.Urls
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Instrumented because the bundled SQLite ships as a native library — these
 * cannot run on a desktop JVM.
 */
class SqliteIndexStoreTest {

    private lateinit var store: SqliteIndexStore
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        databaseFile = File(context.cacheDir, "index-test-${System.nanoTime()}.db")
        store = open()
    }

    @After
    fun tearDown() {
        store.close()
        databaseFile.delete()
    }

    private fun open(config: IndexConfig = IndexConfig()) =
        SqliteIndexStore(BundledSQLiteDriver(), databaseFile.absolutePath, config)

    @Test
    fun storesAndReturnsADocument() = runBlocking {
        store.put(document("https://example.com/a", title = "Authentication guide"), TTL)

        val loaded = store.get("https://example.com/a")

        assertNotNull(loaded)
        assertEquals("Authentication guide", loaded!!.title)
        assertEquals("https://example.com/a", loaded.urls.requested)
        // Anything served from storage is reported as such regardless of how it
        // was originally fetched.
        assertEquals(Tier.INDEX, loaded.tier)
    }

    @Test
    fun returnsNullForAnUnknownUrl() = runBlocking {
        assertNull(store.get("https://example.com/never-seen"))
    }

    @Test
    fun roundTripsLinksAndMetadata() = runBlocking {
        val document = document("https://example.com/meta").copy(
            links = listOf(Link("https://example.com/next", "Next page", "next")),
            metadata = PageMetadata(
                description = "A description",
                author = "An author",
                siteName = "Example",
                jsonLd = listOf("""{"@type":"Article"}"""),
                openGraph = mapOf("title" to "OG title"),
            ),
        )
        store.put(document, TTL)

        val loaded = store.get("https://example.com/meta")!!

        assertEquals("Next page", loaded.links.single().text)
        assertEquals("A description", loaded.metadata.description)
        assertEquals("Example", loaded.metadata.siteName)
        assertEquals("OG title", loaded.metadata.openGraph["title"])
        assertTrue(loaded.metadata.jsonLd.single().contains("Article"))
    }

    @Test
    fun expiredDocumentsAreNotReturned() = runBlocking {
        store.put(document("https://example.com/stale"), ttlMillis = -1_000)

        assertNull(store.get("https://example.com/stale"))
    }

    @Test
    fun rewritingAUrlReplacesRatherThanDuplicates() = runBlocking {
        store.put(document("https://example.com/p", title = "First"), TTL)
        store.put(document("https://example.com/p", title = "Second"), TTL)

        assertEquals("Second", store.get("https://example.com/p")!!.title)
        assertEquals(1L, store.stats().documentCount)
    }

    @Test
    fun findsDocumentsByKeyword() = runBlocking {
        store.put(document("https://example.com/1", text = "Rate limiting is applied per domain"), TTL)
        store.put(document("https://example.com/2", text = "Cookies are isolated per task"), TTL)

        val results = store.search("limiting", limit = 10)

        assertEquals(1, results.size)
        assertEquals("https://example.com/1", results.single().url)
        assertTrue(results.single().fromIndex)
    }

    @Test
    fun ranksTitleMatchesAboveBodyMatches() = runBlocking {
        store.put(document("https://example.com/body", title = "Something else", text = "a passing mention of tokens"), TTL)
        store.put(document("https://example.com/title", title = "Tokens", text = "unrelated wording here"), TTL)

        val results = store.search("tokens", limit = 10)

        assertEquals(2, results.size)
        assertEquals("https://example.com/title", results.first().url)
    }

    @Test
    fun matchesAcrossWordFormsThroughStemming() = runBlocking {
        store.put(document("https://example.com/s", text = "the crawler is crawling politely"), TTL)

        assertEquals(1, store.search("crawl", limit = 10).size)
    }

    @Test
    fun treatsPunctuationAsTextRatherThanQuerySyntax() = runBlocking {
        store.put(document("https://example.com/q", text = "plain content"), TTL)

        // Unescaped, these are FTS5 operators and would raise a syntax error.
        assertTrue(store.search("\"unbalanced", limit = 10).isEmpty())
        assertTrue(store.search("a AND OR b", limit = 10).isEmpty())
        assertTrue(store.search("*", limit = 10).isEmpty())
    }

    @Test
    fun searchSkipsExpiredDocuments() = runBlocking {
        store.put(document("https://example.com/gone", text = "vanishing content"), ttlMillis = -1_000)

        assertTrue(store.search("vanishing", limit = 10).isEmpty())
    }

    @Test
    fun searchHonoursTheLimit() = runBlocking {
        repeat(5) { store.put(document("https://example.com/n$it", text = "shared keyword body"), TTL) }

        assertEquals(2, store.search("keyword", limit = 2).size)
    }

    @Test
    fun deletedDocumentsLeaveNoSearchResidue() = runBlocking {
        store.put(document("https://example.com/temp", text = "temporary indexed content"), ttlMillis = -1_000)
        store.prune()

        assertTrue(store.search("temporary", limit = 10).isEmpty())
        assertEquals(0L, store.stats().documentCount)
    }

    @Test
    fun pruneRemovesExpiredButKeepsFresh() = runBlocking {
        store.put(document("https://example.com/fresh"), TTL)
        store.put(document("https://example.com/old"), ttlMillis = -1_000)

        store.prune()

        assertEquals(1L, store.stats().documentCount)
        assertNotNull(store.get("https://example.com/fresh"))
    }

    @Test
    fun pruneEvictsWhenOverTheSizeCap() = runBlocking {
        store.close()
        store = open(IndexConfig(maxSizeBytes = 1))
        repeat(20) { store.put(document("https://example.com/e$it"), TTL) }

        val before = store.stats().documentCount
        store.prune()

        assertTrue("expected eviction below $before", store.stats().documentCount < before)
    }

    @Test
    fun recordsAndReadsBackDomainRouting() = runBlocking {
        val routing = DomainRouting(
            domain = "example.com",
            tier = Tier.BROWSER.name,
            lastSuccessAt = 1_700_000_000_000,
            failureCount = 2,
            backoffUntil = 1_700_000_100_000,
        )
        store.recordRouting(routing)

        assertEquals(routing, store.routingFor("example.com"))
    }

    @Test
    fun routingIsNullForAnUnseenDomain() = runBlocking {
        assertNull(store.routingFor("unseen.test"))
    }

    @Test
    fun recordingRoutingAgainOverwritesTheEarlierEntry() = runBlocking {
        store.recordRouting(DomainRouting("example.com", Tier.HTTP.name, 1L))
        store.recordRouting(DomainRouting("example.com", Tier.BROWSER.name, 2L))

        assertEquals(Tier.BROWSER.name, store.routingFor("example.com")!!.tier)
    }

    @Test
    fun statsCountDocumentsAndSize() = runBlocking {
        store.put(document("https://example.com/s1"), TTL)
        store.put(document("https://example.com/s2"), TTL)

        val stats = store.stats()

        assertEquals(2L, stats.documentCount)
        assertTrue(stats.sizeBytes > 0)
    }

    @Test
    fun dataSurvivesReopening() = runBlocking {
        store.put(document("https://example.com/persist", title = "Persisted"), TTL)
        store.close()

        store = open()

        assertEquals("Persisted", store.get("https://example.com/persist")!!.title)
    }

    private fun document(
        url: String,
        title: String = "A title",
        text: String = "Body text for the document",
    ) = Document(
        urls = Urls(requested = url, final = url, canonical = null),
        title = title,
        text = text,
        markdown = text,
        contentHash = text.hashCode().toString(),
        fetchedAt = System.currentTimeMillis(),
        tier = Tier.HTTP,
        source = Source.WEB,
        statusCode = 200,
        contentType = "text/html",
    )

    private companion object {
        const val TTL = 60_000L
    }
}
