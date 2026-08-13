package com.fetch.core

import com.fetch.core.config.ExtractionConfig
import com.fetch.core.extract.Extractor
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Extraction against the page shapes the engine actually meets: a blog, a
 * documentation site, a news article, a product page, a question thread and a
 * client-rendered shell.
 *
 * Each case states what must survive and what must not, so a change that
 * improves one layout and quietly ruins another fails here rather than in
 * someone's index.
 *
 * Fixtures are hand-written to mirror the structure of the real generators —
 * WordPress, Docusaurus, schema.org news, storefront, Q&A — not copied from
 * live sites, which would make the suite depend on their markup and licensing.
 */
class SiteFixtureTest {

    private val extractor = Extractor(ExtractionConfig())

    private fun load(name: String): String =
        checkNotNull(javaClass.classLoader?.getResourceAsStream("fixtures/$name")) {
            "missing fixture: $name"
        }.bufferedReader().readText()

    private fun check(
        fixture: String,
        url: String,
        expectedTitle: String? = null,
        mustContain: List<String>,
        mustNotContain: List<String>,
        minLength: Int = 200,
    ) {
        val result = extractor.extract(load(fixture), url)

        expectedTitle?.let {
            assertTrue("$fixture: title was '${result.title}'", result.title.orEmpty().contains(it))
        }
        assertTrue("$fixture: only ${result.text.length} chars extracted", result.text.length >= minLength)
        mustContain.forEach {
            assertTrue("$fixture: lost content '$it'", result.text.contains(it, ignoreCase = true))
        }
        mustNotContain.forEach {
            assertTrue("$fixture: kept boilerplate '$it'", !result.text.contains(it, ignoreCase = true))
        }
    }

    @Test
    fun `blog article keeps the post and drops comments, sharing and sidebar`() {
        check(
            fixture = "wordpress-blog.html",
            url = "https://blog.example.com/connection-pooling/",
            expectedTitle = "Connection Pooling",
            mustContain = listOf(
                "Opening a database connection is expensive",
                "moves the queue from the application into the database",
                "Size the pool to the database",
            ),
            mustNotContain = listOf(
                "Skip to content",
                "Share this",
                "Great post, this helped me",
                "You might also like",
                "Subscribe to the newsletter",
                "This site uses cookies",
                "All rights reserved",
            ),
        )
    }

    @Test
    fun `documentation keeps prose and code, drops the sidebar and pager`() {
        check(
            fixture = "docs-site.html",
            url = "https://docs.example.com/guide/cancellation",
            expectedTitle = "Cancellation",
            mustContain = listOf(
                "Every call in the SDK is cancellable",
                "Cancellation is cooperative",
                "job.cancel()",
                "connectTimeout",
            ),
            mustNotContain = listOf(
                "Error handling",
                "Previous: Quick start",
                "Privacy",
            ),
        )
    }

    @Test
    fun `news article keeps the story and drops consent, ads and trending rails`() {
        check(
            fixture = "news-article.html",
            url = "https://news.example.com/2026/03/data-brokers",
            expectedTitle = "data brokers",
            mustContain = listOf(
                "opened a formal inquiry",
                "gather information from public records",
                "operates almost entirely outside public view",
            ),
            mustNotContain = listOf(
                "use cookies to personalise",
                "Most read",
                "Markets close higher",
                "What the new privacy law changes",
            ),
        )
    }

    @Test
    fun `product page keeps the description and drops upsell rails`() {
        check(
            fixture = "ecommerce-product.html",
            url = "https://store.example.com/products/field-notebook-a5",
            expectedTitle = "Field Notebook",
            mustContain = listOf(
                "stitched binding that opens flat",
                "192 pages",
            ),
            mustNotContain = listOf(
                "Customers also bought",
                "Subscribe for ten percent",
            ),
        )
    }

    @Test
    fun `question thread keeps question and answer, drops the related sidebar`() {
        check(
            fixture = "forum-thread.html",
            url = "https://qa.example.com/questions/883/coroutine-cancel",
            mustContain = listOf(
                "the loop inside keeps printing",
                "Cancellation is cooperative",
                "isActive",
            ),
            mustNotContain = listOf(
                "How to cancel a flow collection",
                "Get the weekly digest",
            ),
        )
    }

    @Test
    fun `client-rendered shell yields nothing worth indexing`() {
        val result = extractor.extract(load("spa-shell.html"), "https://app.example.com")

        // The router reads exactly this to decide whether to escalate, so the
        // signal has to stay honest: no invented content, no script leakage.
        assertTrue("expected an empty extraction, got: ${result.text}", result.text.length < 50)
        assertTrue(!result.text.contains("__INITIAL_STATE__"))
        assertTrue(!result.text.contains("main.8f3a21.js"))
    }

    @Test
    fun `structured data survives on pages that publish it`() {
        val news = extractor.extract(load("news-article.html"), "https://news.example.com/x")
        val product = extractor.extract(load("ecommerce-product.html"), "https://store.example.com/x")

        assertTrue(news.metadata.jsonLd.single().contains("NewsArticle"))
        assertTrue(news.metadata.author == "A Reporter")
        assertTrue(product.metadata.jsonLd.single().contains("\"price\":\"14.00\""))
    }

    @Test
    fun `canonical urls are read from every fixture that declares one`() {
        listOf(
            "wordpress-blog.html" to "https://blog.example.com/connection-pooling/",
            "docs-site.html" to "https://docs.example.com/guide/cancellation",
            "news-article.html" to "https://news.example.com/2026/03/data-brokers",
            "ecommerce-product.html" to "https://store.example.com/products/field-notebook-a5",
        ).forEach { (fixture, expected) ->
            val result = extractor.extract(load(fixture), "https://wrong.example.com/redirected")
            assertTrue("$fixture: canonical was ${result.canonicalUrl}", result.canonicalUrl == expected)
        }
    }

    @Test
    fun `links are absolute and exclude stripped navigation`() {
        val result = extractor.extract(load("wordpress-blog.html"), "https://blog.example.com/connection-pooling/")

        assertTrue(result.links.all { it.url.startsWith("http") })
        assertTrue("navigation links leaked", result.links.none { it.url.endsWith("/archive") })
    }
}
