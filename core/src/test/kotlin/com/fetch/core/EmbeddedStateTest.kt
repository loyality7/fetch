package com.fetch.core

import com.fetch.core.config.ExtractionConfig
import com.fetch.core.extract.Extractor
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Client-rendered pages still ship their data as JSON in the markup. Reading it
 * turns a page that would otherwise need a browser into one that does not.
 */
class EmbeddedStateTest {

    private val extractor = Extractor(ExtractionConfig())
    private val prose =
        "The regulator has opened a formal inquiry into companies that collect and resell " +
            "consumer data, saying the practice may harm both privacy and competition."

    @Test
    fun `recovers article text from a hydration payload`() {
        val html = """
            <html><head><title>Story</title></head><body>
              <div id="__next"></div>
              <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"article":{"headline":"Inquiry opened","articleBody":"$prose"}}}}
              </script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://news.example.com/story")

        assertTrue(result.text.contains("formal inquiry"))
    }

    @Test
    fun `recovers from a global assignment`() {
        val html = """
            <html><head><title>App</title></head><body>
              <div id="app"></div>
              <script>window.__NUXT__ = {"data":[{"description":"$prose"}]};</script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://app.example.com")

        assertTrue(result.text.contains("consumer data"))
    }

    @Test
    fun `recovers from structured data when the page body is empty`() {
        val html = """
            <html><head><title>Story</title>
              <script type="application/ld+json">
                {"@type":"NewsArticle","headline":"Inquiry opened","articleBody":"$prose"}
              </script>
            </head><body><div id="root"></div></body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://news.example.com/x")

        assertTrue(result.text.contains("formal inquiry"))
    }

    @Test
    fun `leaves rendered pages alone`() {
        val body = "Server rendered prose that stands entirely on its own merits. ".repeat(20)
        val html = """
            <html><head><title>Rendered</title></head><body>
              <main><p>$body</p></main>
              <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"description":"$prose"}}}
              </script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://example.com")

        assertTrue(result.text.contains("Server rendered prose"))
        // Markup won: the payload is a fallback, not an additional source.
        assertFalse(result.text.contains("formal inquiry"))
    }

    @Test
    fun `ignores urls, hashes and identifiers`() {
        val html = """
            <html><head><title>App</title></head><body><div id="root"></div>
              <script id="__NEXT_DATA__" type="application/json">
                {"buildId":"a3f9c81e77b24d0e91ff23aa19bc4d55",
                 "assetPrefix":"https://cdn.example.com/static/chunks/main-8f3a21.js",
                 "locales":["en-GB","en-US"],
                 "summary":"$prose"}
              </script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://app.example.com")

        assertTrue(result.text.contains("formal inquiry"))
        assertFalse(result.text.contains("cdn.example.com"))
        assertFalse(result.text.contains("a3f9c81e"))
    }

    @Test
    fun `ignores short values that happen to sit under a content key`() {
        val html = """
            <html><head><title>App</title></head><body><div id="root"></div>
              <script type="application/json">{"title":"Home","description":"Welcome"}</script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://app.example.com")

        // Nothing worth indexing: the router must still see a thin page and
        // escalate rather than accept a fragment.
        assertTrue(result.text.length < 50)
    }

    @Test
    fun `returns nothing for a shell that carries no payload`() {
        val html = """
            <html><head><title>App</title></head><body>
              <div id="root"></div><script src="/static/main.js"></script>
            </body></html>
        """.trimIndent()

        assertTrue(extractor.extract(html, "https://app.example.com").text.length < 50)
    }

    @Test
    fun `survives malformed json without failing the extraction`() {
        val html = """
            <html><head><title>App</title></head><body><div id="root"></div>
              <script type="application/json">{"broken": </script>
              <script>window.__NUXT__ = not json at all;</script>
            </body></html>
        """.trimIndent()

        assertTrue(extractor.extract(html, "https://app.example.com").text.length < 50)
    }

    @Test
    fun `can be turned off`() {
        val html = """
            <html><head><title>App</title></head><body><div id="root"></div>
              <script id="__NEXT_DATA__" type="application/json">{"summary":"$prose"}</script>
            </body></html>
        """.trimIndent()

        val disabled = Extractor(ExtractionConfig(recoverEmbeddedState = false))

        assertTrue(disabled.extract(html, "https://app.example.com").text.length < 50)
    }

    /** Encodes [inner] as a JSON string value, escaping as a real payload would. */
    private fun nested(inner: String) = inner.replace("\\", "\\\\").replace("\"", "\\\"")

    @Test
    fun `descends into json nested inside a json string`() {
        // Hydration payloads double-encode: the outer value is a string whose
        // contents are more JSON. Keeping the string would index the
        // serialisation rather than the writing inside it.
        val inner = nested("""{"article":{"body":"$prose"}}""")
        val html = """
            <html><head><title>App</title></head><body><div id="root"></div>
              <script id="__NEXT_DATA__" type="application/json">{"payload":"$inner"}</script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://app.example.com")

        assertTrue(result.text.contains("formal inquiry"))
        assertFalse(result.text.contains("article"))
    }

    @Test
    fun `rejects serialised structures that read as long strings`() {
        val toc = nested("""[{"url":"#a","text":"Overview","depth":2},{"url":"#b","text":"Components","depth":2}]""")
        val html = """
            <html><head><title>Docs</title></head><body><div id="root"></div>
              <script id="__NEXT_DATA__" type="application/json">{"toc":"$toc","summary":"$prose"}</script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://docs.example.com")

        assertTrue(result.text.contains("formal inquiry"))
        assertFalse(result.text.contains("depth"))
        assertFalse(result.text.contains("#a"))
    }

    @Test
    fun `rejects component trees that carry markers rather than prose`() {
        val marker = "$" + "r"
        val tree = nested("""[["$marker","MaxWidth","20",{"children":[["$marker","Intro",null]]}]]""")
        val html = """
            <html><head><title>App</title></head><body><div id="root"></div>
              <script type="application/json">{"tree":"$tree","description":"$prose"}</script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://app.example.com")

        assertFalse(result.text.contains("MaxWidth"))
        assertTrue(result.text.contains("formal inquiry"))
    }

    @Test
    fun `joins adjacent fragments from sibling nodes`() {
        val part1 = "The investigation concluded that consumer privacy rules were violated"
        val part2 = "and immediate corrective measures have been implemented by the oversight board."
        val html = """
            <html><head><title>App</title></head><body><div id="root"></div>
              <script id="__NEXT_DATA__" type="application/json">
                {"props":{"pageProps":{"articleBody1":"$part1","articleBody2":"$part2"}}}
              </script>
            </body></html>
        """.trimIndent()

        val result = extractor.extract(html, "https://app.example.com")

        assertTrue(result.text.contains("$part1 $part2"))
    }
}
