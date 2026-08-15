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

        // Test real client-side JavaScript execution rendering content dynamically into the DOM
        val htmlData = "<html><body><div id=\"root\"></div><script>setTimeout(function(){ document.getElementById('root').innerHTML = '<h2>Real JS Dynamic Rendered Title</h2>'; }, 50);</script></body></html>"
        val dataUrl = "data:text/html;charset=utf-8," + java.net.URLEncoder.encode(htmlData, "UTF-8")

        val result = backend.fetch(dataUrl, WaitUntil.NETWORK_IDLE)
        assertNotNull(result.html)
        assertTrue("WebView JS engine must dynamically render content into DOM", result.html.contains("Real JS Dynamic Rendered Title"))
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
