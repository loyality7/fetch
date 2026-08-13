package com.fetch.core

import com.fetch.core.config.HttpConfig
import com.fetch.core.config.SecurityConfig
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.core.net.HttpFetcher
import com.fetch.core.net.SsrfGuard
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
 * MockWebServer binds loopback, which the SSRF guard blocks by design, so these
 * tests allow that one host explicitly — the same escape hatch an embedding app
 * would use to reach its own service.
 */
class HttpFetcherTest {

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

    /** MockWebServer's loopback hostname varies by platform, so read it back. */
    private fun loopbackHost() = server.url("/").host

    private fun fetcher(config: HttpConfig = HttpConfig()) =
        HttpFetcher(config, SsrfGuard(SecurityConfig(allowedPrivateHosts = setOf(loopbackHost()))))

    @Test
    fun `returns body, status and content type`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("<html><body>hello</body></html>")
                .setHeader("Content-Type", "text/html; charset=utf-8"),
        )

        val result = fetcher().fetch(server.url("/page").toString())

        assertEquals(200, result.statusCode)
        assertTrue(result.body.contains("hello"))
        assertTrue(result.contentType!!.startsWith("text/html"))
    }

    @Test
    fun `sends the configured user agent`() = runTest {
        server.enqueue(MockResponse().setBody("ok"))

        fetcher(HttpConfig(userAgent = "fetch-test/1.0")).fetch(server.url("/").toString())

        assertEquals("fetch-test/1.0", server.takeRequest().getHeader("User-Agent"))
    }

    @Test
    fun `follows redirects and reports the final url`() = runTest {
        server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "/final"))
        server.enqueue(MockResponse().setBody("arrived"))

        val result = fetcher().fetch(server.url("/start").toString())

        assertTrue(result.finalUrl.endsWith("/final"))
        assertTrue(result.body.contains("arrived"))
    }

    @Test
    fun `rejects a body larger than the configured cap`() = runTest {
        server.enqueue(MockResponse().setBody("x".repeat(5_000)))

        val error = assertThrows(EngineException::class.java) {
            kotlinx.coroutines.runBlocking {
                fetcher(HttpConfig(maxResponseBytes = 1_000)).fetch(server.url("/big").toString())
            }
        }

        assertEquals(ErrorCode.RESPONSE_TOO_LARGE, error.code)
    }

    @Test
    fun `surfaces an http error status without throwing`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("missing"))

        val result = fetcher().fetch(server.url("/gone").toString())

        assertEquals(404, result.statusCode)
    }

    @Test
    fun `maps a connection failure to a structured error`() = runTest {
        val port = server.port
        val host = loopbackHost()
        val closed = HttpFetcher(HttpConfig(), SsrfGuard(SecurityConfig(allowedPrivateHosts = setOf(host))))
        server.shutdown()

        val error = assertThrows(EngineException::class.java) {
            kotlinx.coroutines.runBlocking {
                closed.fetch("http://$host:$port/unreachable")
            }
        }

        assertEquals(ErrorCode.NETWORK_UNAVAILABLE, error.code)
    }

    @Test
    fun `blocks a url the guard rejects before any request is made`() = runTest {
        val strict = HttpFetcher(HttpConfig(), SsrfGuard(SecurityConfig()))

        val error = assertThrows(EngineException::class.java) {
            kotlinx.coroutines.runBlocking { strict.fetch("http://169.254.169.254/") }
        }

        assertEquals(ErrorCode.SSRF_BLOCKED, error.code)
        assertEquals(0, server.requestCount)
    }
}
