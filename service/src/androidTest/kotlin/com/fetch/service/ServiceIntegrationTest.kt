package com.fetch.service

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.engine.Health
import com.fetch.core.engine.WebEngine
import com.fetch.core.model.AskResponse
import com.fetch.core.model.CacheMode
import com.fetch.core.model.Document
import com.fetch.core.model.Evidence
import com.fetch.core.model.PageMetadata
import com.fetch.core.model.SearchResponse
import com.fetch.core.model.Source
import com.fetch.core.model.Tier
import com.fetch.core.model.Urls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
public class ServiceIntegrationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    public fun testApiTokenManagerStoreAndRetrieve() {
        val manager = ApiTokenManager(context)
        val token = manager.getOrCreateToken()

        assertNotNull(token)
        assertTrue(token.isNotBlank())

        val retrieved = manager.getToken()
        assertEquals(token, retrieved)
    }

    @Test
    public fun testApiTokenManagerRotation() {
        val manager = ApiTokenManager(context)
        val initialToken = manager.getOrCreateToken()
        val rotatedToken = manager.rotateToken()

        assertTrue(rotatedToken.isNotBlank())
        assertTrue(initialToken != rotatedToken)
        assertEquals(rotatedToken, manager.getToken())
    }

    @Test
    public fun testLocalApiServerEndToEnd() {
        val manager = ApiTokenManager(context)
        val token = manager.getOrCreateToken()
        val fakeEngine = FakeWebEngine()

        val server = LocalApiServer(fakeEngine, token, port = 8479)
        server.start()

        try {
            val url = java.net.URL("http://127.0.0.1:8479/v1/health")
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Authorization", "Bearer $token")
            connection.setRequestProperty("X-Request-ID", "test-req-123")

            val responseCode = connection.responseCode
            assertEquals(200, responseCode)

            val body = connection.inputStream.bufferedReader().readText()
            assertTrue(body.contains("engine_version"))
            assertTrue(body.contains("test-req-123"))
        } finally {
            server.stop()
        }
    }

    @Test
    public fun testAllV1Endpoints() {
        val manager = ApiTokenManager(context)
        val token = manager.getOrCreateToken()
        val fakeEngine = FakeWebEngine()

        val server = LocalApiServer(fakeEngine, token, port = 8480)
        server.start()

        try {
            // /v1/search
            postJson("http://127.0.0.1:8480/v1/search", token, """{"query":"kotlin","max_results":5}""").also { (code, body) ->
                assertEquals(200, code)
                assertTrue(body.contains("kotlin"))
            }

            // /v1/open
            postJson("http://127.0.0.1:8480/v1/open", token, """{"url":"https://kotlinlang.org"}""").also { (code, body) ->
                assertEquals(200, code)
                assertTrue(body.contains("https://kotlinlang.org"))
            }

            // /v1/extract
            postJson("http://127.0.0.1:8480/v1/extract", token, """{"content":"# Hello"}""").also { (code, body) ->
                assertEquals(200, code)
                assertTrue(body.contains("content"))
            }

            // /v1/find
            postJson("http://127.0.0.1:8480/v1/find", token, """{"url":"https://kotlinlang.org","query":"test"}""").also { (code, body) ->
                assertEquals(200, code)
            }

            // /v1/add
            postJson("http://127.0.0.1:8480/v1/add", token, """{"content":"User text","title":"Note"}""").also { (code, body) ->
                assertEquals(200, code)
            }

            // /v1/ask
            postJson("http://127.0.0.1:8480/v1/ask", token, """{"query":"what is kotlin?"}""").also { (code, body) ->
                assertEquals(200, code)
                assertTrue(body.contains("context"))
            }
        } finally {
            server.stop()
        }
    }

    private fun postJson(urlString: String, token: String, jsonBody: String): Pair<Int, String> {
        val url = java.net.URL(urlString)
        val connection = url.openConnection() as java.net.HttpURLConnection
        connection.requestMethod = "POST"
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        connection.doOutput = true
        connection.outputStream.use { it.write(jsonBody.toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val responseText = stream?.bufferedReader()?.readText().orEmpty()
        return code to responseText
    }
}

private class FakeWebEngine : WebEngine {
    override suspend fun search(query: String, maxResults: Int, cacheMode: CacheMode): SearchResponse {
        return SearchResponse(query = query, results = emptyList(), durationMs = 10, fromIndex = true)
    }

    override suspend fun open(url: String, cacheMode: CacheMode): Document {
        return Document(
            urls = Urls(requested = url, final = url, canonical = null),
            title = "Test",
            text = "Test content",
            markdown = "# Test",
            contentHash = "hash",
            fetchedAt = System.currentTimeMillis(),
            tier = Tier.INDEX,
            source = Source.WEB,
            statusCode = 200,
            contentType = "text/html",
            links = emptyList(),
            metadata = PageMetadata()
        )
    }

    override suspend fun extract(url: String?, content: String?): Document {
        return open(url ?: "http://localhost")
    }

    override suspend fun find(url: String, query: String, maxPassages: Int): List<Evidence> {
        return emptyList()
    }

    override suspend fun add(content: String, title: String?, url: String?, source: Source): Document {
        return open(url ?: "http://localhost")
    }

    override suspend fun ask(query: String, maxSources: Int): AskResponse {
        return AskResponse(query = query, context = "context", evidence = emptyList(), durationMs = 5)
    }

    override suspend fun health(): Health {
        return Health(
            engineVersion = "0.1.0-test",
            capabilities = mapOf("fts5" to true),
            indexedDocuments = 42,
            indexSizeBytes = 1024,
            browserAvailable = false,
            sources = emptyMap()
        )
    }

    override fun close() {}
}
