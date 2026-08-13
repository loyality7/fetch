package com.fetch.core

import com.fetch.core.config.ExtractionConfig
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.core.extract.ContentType
import com.fetch.core.extract.Extractor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * An HTML parser accepts anything and returns punctuation as prose, so what a
 * response is has to be settled before it is read.
 */
class ContentTypeTest {

    @Test
    fun `trusts a useful declared type`() {
        assertEquals(ContentType.HTML, ContentType.detect("text/html; charset=utf-8", "<html>"))
        assertEquals(ContentType.JSON, ContentType.detect("application/json", "{}"))
        assertEquals(ContentType.FEED, ContentType.detect("application/atom+xml", "<feed>"))
        assertEquals(ContentType.XML, ContentType.detect("application/xml", "<catalog><item/></catalog>"))
        assertEquals(ContentType.MARKDOWN, ContentType.detect("text/markdown", "# Title"))
    }

    @Test
    fun `sniffs the body when the declared type says nothing useful`() {
        // Endpoints answer text/plain for anything, so the header alone is not
        // enough to decide with.
        assertEquals(ContentType.JSON, ContentType.detect("text/plain", """{"a":1}"""))
        assertEquals(ContentType.HTML, ContentType.detect("text/plain", "<!DOCTYPE html><html>"))
        assertEquals(ContentType.JSON, ContentType.detect(null, """[{"a":1}]"""))
        assertEquals(ContentType.TEXT, ContentType.detect(null, "just some words"))
    }

    @Test
    fun `separates feeds from other xml`() {
        assertEquals(ContentType.FEED, ContentType.detect("application/xml", "<?xml version=\"1.0\"?><rss><channel>"))
        assertEquals(ContentType.XML, ContentType.detect("application/xml", "<?xml version=\"1.0\"?><catalog>"))
    }

    @Test
    fun `marks binary types as unsupported rather than guessing`() {
        assertEquals(ContentType.UNSUPPORTED, ContentType.detect("image/png", "PNG"))
        assertEquals(ContentType.UNSUPPORTED, ContentType.detect("application/pdf", "%PDF-1.7"))
    }
}

class StructuredExtractionTest {

    private val extractor = Extractor(ExtractionConfig())

    @Test
    fun `renders json as readable key and value lines`() {
        val body = """
            {"full_name":"square/okhttp","description":"An HTTP client for the JVM",
             "stargazers_count":45000,"license":{"name":"Apache License 2.0"},
             "topics":["http","android","kotlin"]}
        """.trimIndent()

        val result = extractor.extract(body, "https://api.example.com/repos/square/okhttp", "application/json")

        assertEquals("square/okhttp", result.title)
        assertTrue(result.text.contains("description: An HTTP client for the JVM"))
        assertTrue(result.text.contains("stargazers_count: 45000"))
        // Nested objects and arrays stay searchable rather than being flattened away.
        assertTrue(result.text.contains("Apache License 2.0"))
        assertTrue(result.text.contains("- android"))
        assertEquals("An HTTP client for the JVM", result.metadata.description)
    }

    @Test
    fun `keeps json fenced in markdown so structure survives`() {
        val result = extractor.extract("""{"a":1}""", "https://api.example.com/x", "application/json")

        assertTrue(result.markdown.startsWith("```json"))
    }

    @Test
    fun `collects links an endpoint points onwards to`() {
        val body = """{"url":"https://api.example.com/next","html_url":"https://example.com/page","count":3}"""

        val result = extractor.extract(body, "https://api.example.com/x", "application/json")

        assertEquals(2, result.links.size)
        assertTrue(result.links.any { it.url == "https://example.com/page" })
    }

    @Test
    fun `never returns raw braces as prose`() {
        val body = """{"nested":{"deep":{"value":"content"}}}"""

        val result = extractor.extract(body, "https://api.example.com/x", "application/json")

        assertFalse(result.text.contains("{"))
        assertTrue(result.text.contains("content"))
    }

    @Test
    fun `falls back to plain text for json that does not parse`() {
        val result = extractor.extract("""{"broken":""", "https://api.example.com/x", "application/json")

        assertTrue(result.text.contains("broken"))
    }

    @Test
    fun `reads a feed as its entries`() {
        val body = """
            <?xml version="1.0"?>
            <rss version="2.0"><channel>
              <title>Example Feed</title>
              <item>
                <title>First post</title>
                <link>https://example.com/1</link>
                <description>Something about connection pooling</description>
              </item>
              <item>
                <title>Second post</title>
                <link>https://example.com/2</link>
                <description><![CDATA[Something about <b>cancellation</b>]]></description>
              </item>
            </channel></rss>
        """.trimIndent()

        val result = extractor.extract(body, "https://example.com/feed.xml", "application/rss+xml")

        assertEquals("Example Feed", result.title)
        assertTrue(result.text.contains("First post"))
        assertTrue(result.text.contains("connection pooling"))
        // Markup inside CDATA is content, not structure.
        assertTrue(result.text.contains("cancellation"))
        assertFalse(result.text.contains("<b>"))
        assertEquals(2, result.links.size)
    }

    @Test
    fun `reads an atom feed with link attributes`() {
        val body = """
            <?xml version="1.0"?>
            <feed xmlns="http://www.w3.org/2005/Atom">
              <title>Releases</title>
              <entry>
                <title>Version 2.0</title>
                <link href="https://example.com/releases/2.0"/>
                <summary>Adds cancellation support</summary>
              </entry>
            </feed>
        """.trimIndent()

        val result = extractor.extract(body, "https://example.com/atom", "application/atom+xml")

        assertTrue(result.text.contains("Version 2.0"))
        assertEquals("https://example.com/releases/2.0", result.links.single().url)
    }

    @Test
    fun `strips tags from generic xml`() {
        val body = "<?xml version=\"1.0\"?><catalog><book><title>Kotlin in Action</title></book></catalog>"

        val result = extractor.extract(body, "https://example.com/catalog.xml", "application/xml")

        assertTrue(result.text.contains("Kotlin in Action"))
        assertFalse(result.text.contains("<title>"))
    }

    @Test
    fun `keeps markdown as it was written and reads its heading`() {
        val body = "# Cancellation\n\nEvery call is cancellable.\n\n- one\n- two"

        val result = extractor.extract(body, "https://example.com/doc.md", "text/markdown")

        assertEquals("Cancellation", result.title)
        assertTrue(result.markdown.contains("- one"))
    }

    @Test
    fun `refuses binary content instead of indexing noise`() {
        val error = assertThrows(EngineException::class.java) {
            extractor.extract("PNG\r\n", "https://example.com/i.png", "image/png")
        }

        assertEquals(ErrorCode.UNSUPPORTED_CONTENT, error.code)
    }

    @Test
    fun `html is unaffected by the type check`() {
        val body = "<html><head><title>Page</title></head><body><main><p>${"Real prose here. ".repeat(40)}</p></main></body></html>"

        val result = extractor.extract(body, "https://example.com", "text/html")

        assertEquals("Page", result.title)
        assertTrue(result.text.contains("Real prose here"))
    }
}
