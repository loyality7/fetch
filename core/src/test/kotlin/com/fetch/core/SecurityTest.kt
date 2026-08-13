package com.fetch.core

import com.fetch.core.config.SecurityConfig
import com.fetch.core.error.ErrorCode
import com.fetch.core.error.EngineException
import com.fetch.core.net.SsrfGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SsrfGuardTest {

    private val guard = SsrfGuard(SecurityConfig())

    @Test
    fun `blocks loopback by name and by literal`() {
        assertBlocked("http://localhost/admin")
        assertBlocked("http://127.0.0.1:8080/")
        assertBlocked("http://[::1]/")
    }

    @Test
    fun `blocks private ranges`() {
        assertBlocked("http://10.0.0.1/")
        assertBlocked("http://192.168.1.1/")
        assertBlocked("http://172.16.0.1/")
    }

    @Test
    fun `blocks link-local and cloud metadata addresses`() {
        assertBlocked("http://169.254.169.254/latest/meta-data/")
    }

    @Test
    fun `blocks carrier-grade nat range`() {
        assertBlocked("http://100.64.0.1/")
    }

    @Test
    fun `blocks non-http schemes by default`() {
        assertBlocked("file:///etc/passwd")
        assertBlocked("content://com.example.provider/secret")
        assertBlocked("javascript:alert(1)")
    }

    @Test
    fun `blocks a malformed url rather than passing it through`() {
        assertBlocked("not a url at all")
    }

    @Test
    fun `allows a private host the embedding app explicitly permitted`() {
        val permissive = SsrfGuard(SecurityConfig(allowedPrivateHosts = setOf("localhost")))

        permissive.check("http://localhost:9000/")
    }

    private fun assertBlocked(url: String) {
        val error = assertThrows(EngineException::class.java) { guard.check(url) }
        assertEquals("expected $url to be blocked", ErrorCode.SSRF_BLOCKED, error.code)
    }
}

class ErrorModelTest {

    @Test
    fun `transient failures are retryable and policy failures are not`() {
        assertEquals(true, ErrorCode.TIMEOUT.retryable)
        assertEquals(true, ErrorCode.RATE_LIMITED.retryable)
        assertEquals(false, ErrorCode.SSRF_BLOCKED.retryable)
        assertEquals(false, ErrorCode.CANCELLED.retryable)
    }

    @Test
    fun `exception carries the code retryability`() {
        val error = EngineException(ErrorCode.BROWSER_CRASHED, "renderer died")

        assertEquals(true, error.retryable)
        assertEquals(ErrorCode.BROWSER_CRASHED, error.code)
    }
}
