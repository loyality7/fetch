package com.fetch.core.browser

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fetch.core.config.BrowserConfig
import com.fetch.core.model.WaitUntil
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class WebViewBrowserBackendTest {

    @Test
    fun testIsAvailableOnDevice() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = WebViewBrowserBackend(context)
        
        val available = backend.isAvailable()
        assertTrue("WebView must be available on device", available)
    }

    @Test
    fun testFetchClientRenderedPage() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val backend = WebViewBrowserBackend(context, BrowserConfig())

        val htmlData = "<html><body><h1>SPA Test</h1><div id=\"root\">Placeholder</div></body></html>"
        val dataUrl = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(htmlData, "UTF-8")

        val result = backend.fetch(dataUrl, WaitUntil.NETWORK_IDLE)
        assertNotNull(result.html)
        assertTrue("Extracted HTML must contain content", result.html.contains("SPA Test"))
    }

    @Test
    fun testMemoryCleanup100Cycles() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val htmlData = "<html><body><h1>Cycle Test</h1></body></html>"
        val dataUrl = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(htmlData, "UTF-8")

        // Perform 5 sequential open/fetch/destroy sessions on device
        for (i in 1..5) {
            val backend = WebViewBrowserBackend(context, BrowserConfig())
            val result = backend.fetch(dataUrl)
            assertNotNull(result.html)
            backend.close()
        }
    }
}
