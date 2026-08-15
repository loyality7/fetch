package com.fetch.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.config.EngineConfig
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import com.fetch.core.sdk.FetchEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AdversarialInputTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testFileAndUnregisteredSchemes() = runBlocking {
        val engine = FetchEngine.create(context)

        try {
            engine.open("file:///etc/passwd")
            fail("Should reject file:// scheme")
        } catch (e: EngineException) {
            assertTrue("File scheme must be blocked or unsupported", e.code == ErrorCode.UNSUPPORTED_CONTENT || e.code == ErrorCode.BLOCKED || e.code == ErrorCode.SSRF_BLOCKED)
        }

        try {
            engine.open("gopher://example.com/resource")
            fail("Should reject unregistered scheme")
        } catch (e: EngineException) {
            assertTrue("Unregistered scheme must be blocked or unsupported", e.code == ErrorCode.UNSUPPORTED_CONTENT || e.code == ErrorCode.BLOCKED || e.code == ErrorCode.SSRF_BLOCKED)
        }

        engine.close()
    }

    @Test
    fun testMalformedAndAdversarialHtmlExtraction() = runBlocking {
        val engine = FetchEngine.create(context)

        // Extremely malformed nested HTML tags
        val malformedHtml = "<html><body>" + "<div><span>".repeat(500) + "Content deeply nested" + "</div></span>".repeat(500) + "</body></html>"
        val doc = engine.extract(url = "https://example.com", content = malformedHtml)

        assertNotNull(doc)
        assertTrue("Extraction must handle deeply nested malformed HTML without crashing", doc.text.contains("Content deeply nested"))

        engine.close()
    }

    @Test
    fun testVeryLargeResponseBodies() = runBlocking {
        val engine = FetchEngine.create(context)

        // 10MB text string passed to extractor
        val largeContent = "Large body sentence repeated. ".repeat(300_000)
        val doc = engine.extract(url = "https://example.com/large", content = largeContent)

        assertNotNull(doc)
        assertTrue("Large body extraction completes safely", doc.text.isNotEmpty())

        engine.close()
    }
}
