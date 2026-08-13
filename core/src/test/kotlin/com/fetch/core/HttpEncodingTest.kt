package com.fetch.core

import com.fetch.core.config.HttpConfig
import com.fetch.core.config.SecurityConfig
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.SsrfGuard
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import okio.GzipSink
import okio.buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Transfer encoding and charset handling.
 *
 * Regression cover: setting Accept-Encoding by hand disables OkHttp's
 * transparent decompression, so a gzipped page arrives as binary and every
 * downstream stage sees noise instead of markup.
 */
class HttpEncodingTest {

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

    private fun fetcher() = HttpFetcher(
        HttpConfig(),
        SsrfGuard(SecurityConfig(allowedPrivateHosts = setOf(server.url("/").host))),
    )

    private fun gzip(content: String): Buffer {
        val result = Buffer()
        GzipSink(result).buffer().use { it.writeUtf8(content) }
        return result
    }

    @Test
    fun `decodes a gzipped response`() = runTest {
        val html = "<html><body><h1>Readable heading</h1></body></html>"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Encoding", "gzip")
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody(gzip(html)),
        )

        val result = fetcher().fetch(server.url("/gz").toString())

        assertEquals(html, result.body)
        assertTrue(result.body.startsWith("<html"))
    }

    @Test
    fun `does not send its own accept-encoding header`() = runTest {
        server.enqueue(MockResponse().setBody("plain"))

        fetcher().fetch(server.url("/").toString())

        // OkHttp adds this itself, and only decompresses while it owns it.
        assertEquals("gzip", server.takeRequest().getHeader("Accept-Encoding"))
    }

    @Test
    fun `decodes a latin-1 body using the declared charset`() = runTest {
        val text = "café naïve"
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/html; charset=ISO-8859-1")
                .setBody(Buffer().write(text.toByteArray(Charsets.ISO_8859_1))),
        )

        val result = fetcher().fetch(server.url("/latin").toString())

        assertTrue(result.body.contains("café"))
        assertFalse(result.body.contains("�"))
    }

    @Test
    fun `falls back to utf-8 when the charset is missing or unknown`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("héllo"))
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=not-a-charset").setBody("héllo"))

        val subject = fetcher()

        assertTrue(subject.fetch(server.url("/a").toString()).body.contains("héllo"))
        assertTrue(subject.fetch(server.url("/b").toString()).body.contains("héllo"))
    }

    @Test
    fun `captures validators for conditional requests`() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("ETag", "\"abc123\"")
                .setHeader("Last-Modified", "Wed, 21 Oct 2026 07:28:00 GMT")
                .setBody("body"),
        )

        val result = fetcher().fetch(server.url("/validators").toString())

        assertEquals("\"abc123\"", result.etag)
        assertEquals("Wed, 21 Oct 2026 07:28:00 GMT", result.lastModified)
    }

    @Test
    fun `reports a missing content type as null rather than guessing`() = runTest {
        server.enqueue(MockResponse().setBody("data").setHeader("Content-Type", ""))

        assertNull(fetcher().fetch(server.url("/none").toString()).contentType?.ifEmpty { null })
    }
}
