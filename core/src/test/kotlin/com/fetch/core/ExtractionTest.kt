package com.fetch.core

import com.fetch.core.config.ExtractionConfig
import com.fetch.core.extract.Extractor
import com.fetch.core.extract.MarkdownWriter
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExtractionTest {

    private val extractor = Extractor(ExtractionConfig())

    @Test
    fun `keeps the article and drops surrounding chrome`() {
        val html = """
            <html><head><title>Docs</title></head><body>
              <nav><a href="/a">Home</a><a href="/b">About</a></nav>
              <article><p>${"The authentication flow uses bearer tokens. ".repeat(12)}</p></article>
              <footer>Copyright notice</footer>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        assertTrue(result.text.contains("bearer tokens"))
        assertFalse(result.text.contains("Copyright notice"))
        assertEquals("Docs", result.title)
    }

    @Test
    fun `prefers a dense content block over a link-heavy one of similar length`() {
        val prose = "Rate limiting is applied per domain rather than globally. ".repeat(12)
        val links = (1..40).joinToString("") { "<a href=\"/p$it\">Section number $it here</a> " }
        val html = "<html><body><div class=\"sidebar\">$links</div><div>$prose</div></body></html>"

        val result = extractor.extract(html, "https://example.com")

        assertTrue(result.text.contains("Rate limiting"))
        assertFalse(result.text.contains("Section number 7"))
    }

    @Test
    fun `strips scripts and styles`() {
        val html = """
            <html><body><main>
              <script>var secret = 'do not index';</script>
              <style>.a { color: red }</style>
              <p>${"Visible body text for the reader. ".repeat(12)}</p>
            </main></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        assertFalse(result.text.contains("do not index"))
        assertFalse(result.text.contains("color: red"))
        assertTrue(result.text.contains("Visible body text"))
    }

    @Test
    fun `resolves relative links against the base url and drops non-http schemes`() {
        val body = "Read more about the API surface below. ".repeat(12)
        val html = """
            <html><body><main><p>$body</p>
              <p><a href="/guide">Guide</a> <a href="mailto:x@example.com">Mail</a></p>
            </main></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com/docs/")

        assertTrue(result.links.any { it.url == "https://example.com/guide" })
        assertFalse(result.links.any { it.url.startsWith("mailto:") })
    }

    @Test
    fun `reads canonical url, description and json-ld`() {
        val body = "Structured metadata is lifted from the head. ".repeat(12)
        val html = """
            <html><head>
              <link rel="canonical" href="https://example.com/canonical">
              <meta name="description" content="A description">
              <meta property="og:site_name" content="Example">
              <script type="application/ld+json">{"@type":"Article"}</script>
            </head><body><main><p>$body</p></main></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com/other")

        assertEquals("https://example.com/canonical", result.canonicalUrl)
        assertEquals("A description", result.metadata.description)
        assertEquals("Example", result.metadata.siteName)
        assertTrue(result.metadata.jsonLd.single().contains("Article"))
    }

    @Test
    fun `respects the extracted character cap`() {
        val html = "<html><body><main><p>${"word ".repeat(5_000)}</p></main></body></html>"
        val capped = Extractor(ExtractionConfig(maxExtractedChars = 100))

        val result = capped.extract(html, "https://example.com")

        assertEquals(100, result.text.length)
        assertTrue(result.markdown.length <= 100)
    }
}

class MarkdownWriterTest {

    private fun convert(html: String) = MarkdownWriter.convert(Jsoup.parse(html).body())

    @Test
    fun `writes headings, emphasis and links`() {
        val markdown = convert("<h2>Title</h2><p>Some <strong>bold</strong> and a <a href='https://e.test/x'>link</a>.</p>")

        assertTrue(markdown.contains("## Title"))
        assertTrue(markdown.contains("**bold**"))
        assertTrue(markdown.contains("[link](https://e.test/x)"))
    }

    @Test
    fun `numbers ordered lists and bullets unordered ones`() {
        assertTrue(convert("<ol><li>first</li><li>second</li></ol>").contains("1. first"))
        assertTrue(convert("<ul><li>alpha</li></ul>").contains("- alpha"))
    }

    @Test
    fun `writes a table with a header separator`() {
        val markdown = convert("<table><tr><th>A</th><th>B</th></tr><tr><td>1</td><td>2</td></tr></table>")

        assertTrue(markdown.contains("| A | B |"))
        assertTrue(markdown.contains("| --- | --- |"))
        assertTrue(markdown.contains("| 1 | 2 |"))
    }

    @Test
    fun `fences preformatted blocks`() {
        assertTrue(convert("<pre>val x = 1</pre>").contains("```"))
    }

    @Test
    fun `collapses runs of blank lines`() {
        assertFalse(convert("<p>a</p><p>b</p><p>c</p>").contains("\n\n\n"))
    }
}
