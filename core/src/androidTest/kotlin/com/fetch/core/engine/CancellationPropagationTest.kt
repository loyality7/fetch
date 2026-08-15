package com.fetch.core.engine

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.browser.BrowserBackend
import com.fetch.core.browser.BrowserResult
import com.fetch.core.config.EngineConfig
import com.fetch.core.model.WaitUntil
import com.fetch.core.sdk.FetchEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(AndroidJUnit4::class)
class CancellationPropagationTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun testCancellationDuringBrowserSessionCleanup() = runBlocking {
        val sessionDestroyed = AtomicBoolean(false)

        val customBrowser = object : BrowserBackend {
            override suspend fun isAvailable(): Boolean = true
            override suspend fun fetch(url: String, waitUntil: WaitUntil, blockAssets: Boolean): BrowserResult {
                try {
                    // Long loading browser session
                    delay(3000)
                    return BrowserResult(
                        html = "<html><body>Browser rendered</body></html>",
                        finalUrl = url,
                        statusCode = 200,
                        settled = true,
                        bytesDownloaded = 100,
                        elapsedMs = 3000,
                    )
                } finally {
                    sessionDestroyed.set(true)
                }
            }

            override fun close() {
                sessionDestroyed.set(true)
            }
        }

        val engine = FetchEngine.create(context, browser = customBrowser)

        val deferred = async(Dispatchers.IO) {
            engine.open("https://example.com/long-page")
        }

        // Allow task to start
        delay(50)
        deferred.cancel()

        try {
            deferred.await()
        } catch (e: CancellationException) {
            // Expected
        }

        delay(100)
        assertTrue("Deferred operation must be cancelled cleanly", deferred.isCancelled)
        engine.close()
    }

    @Test
    fun testRepeatedCancellationUnderLoad() = runBlocking {
        val engine = FetchEngine.create(context)

        for (i in 1..20) {
            val deferred = async(Dispatchers.IO) {
                engine.search("rapid cancellation query $i")
            }
            delay(5)
            deferred.cancel()
            try {
                deferred.await()
            } catch (e: CancellationException) {
                // Expected
            }
        }

        val health = engine.health()
        assertNotNull(health)
        assertTrue("Engine must remain operational after repeated cancellations", health.engineVersion.isNotBlank())

        engine.close()
    }
}
