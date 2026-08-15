package com.fetch.core.discovery

import com.fetch.core.FakeSearchSource
import com.fetch.core.config.DiscoveryConfig
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QueryClassifierAndDiscoveryTest {

    @Test
    fun testClassifierDetectsCode() {
        val verticals = QueryClassifier.classify("fun calculateTotal(a: Int): Int")
        assertTrue(Vertical.CODE in verticals)
    }

    @Test
    fun testClassifierDetectsDocs() {
        val verticals = QueryClassifier.classify("getting started tutorial and manual")
        assertTrue(Vertical.DOCS in verticals)
    }

    @Test
    fun testClassifierDetectsNews() {
        val verticals = QueryClassifier.classify("latest breaking news today")
        assertTrue(Vertical.NEWS in verticals)
    }

    @Test
    fun testClassifierDetectsPapers() {
        val verticals = QueryClassifier.classify("arxiv deep learning abstract")
        assertTrue(Vertical.PAPERS in verticals)
    }

    @Test
    fun testGeneralQueryFansOutToAllSources() = runTest {
        val generalSource = FakeSearchSource("general", listOf("https://a.com"))
        val codeSource = FakeSearchSource("code_only", listOf("https://b.com"))
        
        val discovery = Discovery(listOf(generalSource, codeSource), DiscoveryConfig())
        val response = discovery.search("random search term", 10)
        
        assertEquals(2, response.results.size)
    }

    @Test
    fun testCodeQueryRoutesToCodeAndGeneralSourcesOnly() = runTest {
        val codeSource = FakeSearchSourceWithVertical("code_source", setOf(Vertical.CODE), listOf("https://code.com"))
        val newsSource = FakeSearchSourceWithVertical("news_source", setOf(Vertical.NEWS), listOf("https://news.com"))

        val discovery = Discovery(listOf(codeSource, newsSource), DiscoveryConfig())
        val response = discovery.search("github repo bug stacktrace", 10)

        assertEquals(1, response.results.size)
        assertEquals("https://code.com/", response.results[0].url)
    }
}

private class FakeSearchSourceWithVertical(
    override val name: String,
    override val verticals: Set<Vertical>,
    private val hits: List<String>,
) : SearchSource {
    override val isOfficial: Boolean = true
    override suspend fun search(query: String, maxResults: Int): List<com.fetch.core.model.SearchHit> {
        return hits.mapIndexed { index, url ->
            com.fetch.core.model.SearchHit(
                title = "Title $index",
                url = url,
                snippet = "Snippet $index",
                rank = index,
                sourceName = name,
            )
        }
    }
}
