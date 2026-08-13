package com.fetch.core

import com.fetch.core.discovery.RankFusion
import com.fetch.core.model.SearchHit
import com.fetch.core.net.UrlNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlNormalizerTest {

    @Test
    fun `strips tracking parameters but keeps meaningful ones`() {
        val result = UrlNormalizer.normalize("https://example.com/a?id=7&utm_source=x&gclid=y")
        assertEquals("https://example.com/a?id=7", result)
    }

    @Test
    fun `drops default ports and lowercases host`() {
        assertEquals("https://example.com/", UrlNormalizer.normalize("https://EXAMPLE.com:443"))
        assertEquals("http://example.com/", UrlNormalizer.normalize("http://example.com:80/"))
    }

    @Test
    fun `keeps a non-default port`() {
        assertEquals("https://example.com:8443/a", UrlNormalizer.normalize("https://example.com:8443/a"))
    }
}

class RankFusionTest {

    @Test
    fun `a result found by two sources outranks one found first by a single source`() {
        val sourceA = listOf(hit("https://a.test", 0, "alpha"), hit("https://shared.test", 1, "alpha"))
        val sourceB = listOf(hit("https://b.test", 0, "beta"), hit("https://shared.test", 1, "beta"))

        val fused = RankFusion.fuse(listOf(sourceA, sourceB), k = 60, limit = 10)

        assertEquals("https://shared.test/", fused.first().url)
        assertEquals(listOf("alpha", "beta"), fused.first().sources)
    }

    @Test
    fun `deduplicates urls that differ only by tracking parameters`() {
        val a = listOf(hit("https://x.test/p?utm_source=one", 0, "alpha"))
        val b = listOf(hit("https://x.test/p", 0, "beta"))

        val fused = RankFusion.fuse(listOf(a, b), k = 60, limit = 10)

        assertEquals(1, fused.size)
        assertTrue(fused.first().sources.containsAll(listOf("alpha", "beta")))
    }

    private fun hit(url: String, rank: Int, source: String) =
        SearchHit(title = "t", url = url, snippet = "s", rank = rank, sourceName = source)
}
