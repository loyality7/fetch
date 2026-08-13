package com.fetch.core

import com.fetch.core.config.ExtractionConfig
import com.fetch.core.extract.Extractor
import com.fetch.core.extract.MainContentFinder
import org.jsoup.Jsoup
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Page furniture that sits *inside* the article container: editorial notices,
 * tables of contents, share rails. Tag-based stripping never reaches it, so it
 * ends up at the top of the extracted text where it is most obvious.
 */
class BoilerplateTest {

    private val extractor = Extractor(ExtractionConfig())
    private val body = "Web scraping is used for extracting data from websites. ".repeat(20)

    @Test
    fun `drops editorial notices and namespace tabs above the article`() {
        val html = """
            <html><body><main>
              <div class="hatnote">For broader coverage of this topic, see Data scraping.</div>
              <table class="ambox">
                <tr><td>This article needs more citations. Please help improve it.</td></tr>
              </table>
              <p>$body</p>
            </main></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        assertFalse(result.text.contains("For broader coverage"))
        assertFalse(result.text.contains("needs more citations"))
        assertTrue(result.text.contains("Web scraping is used"))
    }

    @Test
    fun `drops tables of contents, share rails and related links`() {
        val html = """
            <html><body><main>
              <div id="toc"><a href="#a">Section one</a><a href="#b">Section two</a></div>
              <div class="social-share">Share on somewhere</div>
              <div class="related-articles"><a href="/x">Another article entirely</a></div>
              <p>$body</p>
            </main></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        assertFalse(result.text.contains("Section one"))
        assertFalse(result.text.contains("Share on somewhere"))
        assertFalse(result.text.contains("Another article entirely"))
        assertTrue(result.text.contains("Web scraping is used"))
    }

    @Test
    fun `drops consent banners and screen-reader-only text`() {
        val html = """
            <html><body><main>
              <div class="cookie-consent">We use cookies to improve your experience</div>
              <span class="sr-only">Skip to main content</span>
              <p>$body</p>
            </main></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        assertFalse(result.text.contains("We use cookies"))
        assertFalse(result.text.contains("Skip to main content"))
    }

    @Test
    fun `does not remove prose whose class merely contains a matching substring`() {
        val html = """
            <html><body><main>
              <div class="innovation-section"><p>$body</p></div>
            </main></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        // "innovation" contains "nav"; word boundaries are what keep it.
        assertTrue(result.text.contains("Web scraping is used"))
    }

    @Test
    fun `narrows to the deepest container that still holds the text`() {
        val doc = Jsoup.parse(
            """
            <html><body>
              <div id="outer"><div id="middle"><div id="inner"><p>$body</p></div></div></div>
            </body></html>
            """.trimIndent(),
        )

        assertTrue(MainContentFinder.find(doc).id() in setOf("inner", "middle"))
    }

    @Test
    fun `stops narrowing when a container holds several sibling blocks`() {
        val half = "Rate limiting applies per domain and persists across restarts. ".repeat(12)
        val doc = Jsoup.parse(
            """
            <html><body>
              <div id="article"><div id="one"><p>$half</p></div><div id="two"><p>$half</p></div></div>
            </body></html>
            """.trimIndent(),
        )

        // Descending here would silently discard half the article.
        assertTrue(MainContentFinder.find(doc).text().length > half.length * 1.5)
    }

    @Test
    fun `keeps the article when boilerplate is the only other content`() {
        val html = """
            <html><body>
              <div class="navbox"><a href="/a">A</a><a href="/b">B</a></div>
              <div><p>$body</p></div>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        assertTrue(result.text.contains("Web scraping is used"))
    }
}
