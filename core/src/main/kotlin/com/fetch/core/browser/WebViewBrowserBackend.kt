package com.fetch.core.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.fetch.core.config.BrowserConfig
import com.fetch.core.log.Log
import com.fetch.core.model.WaitUntil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.ByteArrayInputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * Production Android WebView browser tier implementation.
 *
 * Runs offscreen on the main thread (Looper.getMainLooper()), handles request interception
 * (blocking images, fonts, media according to [BrowserConfig]), evaluates JavaScript to extract
 * full client-rendered DOM markup, and guarantees memory cleanup.
 */
public class WebViewBrowserBackend(
    private val context: Context,
    private val config: BrowserConfig = BrowserConfig(),
) : BrowserBackend {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val isClosed = AtomicBoolean(false)

    override suspend fun isAvailable(): Boolean = withContext(Dispatchers.Main) {
        if (isClosed.get()) return@withContext false
        runCatching {
            // Verify WebView engine is functional on device
            WebView.getCurrentWebViewPackage() != null || runCatching { WebView(context) }.isSuccess
        }.getOrDefault(false)
    }

    override suspend fun fetch(
        url: String,
        waitUntil: WaitUntil,
        blockAssets: Boolean,
    ): BrowserResult {
        if (isClosed.get()) {
            throw IllegalStateException("WebViewBrowserBackend is closed")
        }

        val startTime = System.currentTimeMillis()

        val result = withTimeoutOrNull(config.totalTaskTimeout) {
            fetchInternal(url, waitUntil, blockAssets)
        } ?: throw com.fetch.core.error.EngineException(
            com.fetch.core.error.ErrorCode.TIMEOUT,
            "Browser navigation timed out after ${config.totalTaskTimeout}",
        )

        val elapsed = System.currentTimeMillis() - startTime
        return result.copy(elapsedMs = elapsed)
    }

    private suspend fun fetchInternal(
        url: String,
        waitUntil: WaitUntil,
        blockAssets: Boolean,
    ): BrowserResult = withContext(Dispatchers.Main) {
        suspendCancellableCoroutine { continuation ->
            val webView = try {
                WebView(context)
            } catch (e: Throwable) {
                continuation.cancel(e)
                return@suspendCancellableCoroutine
            }

            var settled = true
            val isFinished = AtomicBoolean(false)

            webView.settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                loadsImagesAutomatically = !blockAssets || !config.blockImages
                blockNetworkImage = blockAssets && config.blockImages
                mediaPlaybackRequiresUserGesture = true
            }

            fun cleanup() {
                try {
                    webView.stopLoading()
                    webView.onPause()
                    webView.destroy()
                } catch (e: Throwable) {
                    Log.debug("Error destroying webView: ${e.message}")
                }
            }

            continuation.invokeOnCancellation {
                mainHandler.post { cleanup() }
            }

            webView.webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? {
                    if (blockAssets && request != null) {
                        val reqUrl = request.url.toString().lowercase()
                        if (config.blockImages && (reqUrl.endsWith(".png") || reqUrl.endsWith(".jpg") || reqUrl.endsWith(".jpeg") || reqUrl.endsWith(".gif") || reqUrl.endsWith(".webp") || reqUrl.endsWith(".svg"))) {
                            return emptyResponse()
                        }
                        if (config.blockFonts && (reqUrl.endsWith(".ttf") || reqUrl.endsWith(".woff") || reqUrl.endsWith(".woff2") || reqUrl.endsWith(".eot"))) {
                            return emptyResponse()
                        }
                        if (config.blockMedia && (reqUrl.endsWith(".mp4") || reqUrl.endsWith(".webm") || reqUrl.endsWith(".mp3") || reqUrl.endsWith(".wav"))) {
                            return emptyResponse()
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    if (isFinished.getAndSet(true)) return

                    // Post a short delay to allow inline DOM scripts / hydration to settle
                    mainHandler.postDelayed({
                        webView.evaluateJavascript("(document.documentElement && document.documentElement.outerHTML) || document.body.innerHTML") { htmlResult ->
                            val unescapedHtml = unescapeJsString(htmlResult.orEmpty())
                            val finalUrl = view?.url ?: loadedUrl ?: url

                            cleanup()

                            if (continuation.isActive) {
                                continuation.resume(
                                    BrowserResult(
                                        html = unescapedHtml,
                                        finalUrl = finalUrl,
                                        statusCode = 200,
                                        settled = settled,
                                        bytesDownloaded = unescapedHtml.length.toLong(),
                                        elapsedMs = 0L,
                                    )
                                )
                            }
                        }
                    }, 100L)
                }
            }

            if (url.startsWith("data:", ignoreCase = true)) {
                val dataContent = url.substringAfter(",")
                val decodedHtml = java.net.URLDecoder.decode(dataContent, "UTF-8")
                webView.loadDataWithBaseURL("https://localhost/", decodedHtml, "text/html", "UTF-8", null)
            } else {
                webView.loadUrl(url)
            }
        }
    }

    private fun emptyResponse(): WebResourceResponse =
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))

    private fun unescapeJsString(jsOutput: String): String {
        if (jsOutput == "null" || jsOutput.isBlank()) return ""
        return runCatching {
            // Android evaluateJavascript wraps string result in double quotes and JSON escapes quotes and newlines
            val jsonReader = android.util.JsonReader(java.io.StringReader(jsOutput))
            jsonReader.isLenient = true
            val str = jsonReader.nextString()
            jsonReader.close()
            str
        }.getOrElse {
            if (jsOutput.startsWith("\"") && jsOutput.endsWith("\"") && jsOutput.length >= 2) {
                jsOutput.substring(1, jsOutput.length - 1)
                    .replace("\\\"", "\"")
                    .replace("\\n", "\n")
                    .replace("\\r", "\r")
                    .replace("\\t", "\t")
                    .replace("\\\\", "\\")
            } else {
                jsOutput
            }
        }
    }

    override fun close() {
        if (isClosed.compareAndSet(false, true)) {
            // Memory cleanup logic
            mainHandler.post {
                try {
                    android.webkit.WebStorage.getInstance().deleteAllData()
                } catch (e: Throwable) {
                    Log.debug("WebStorage cleanup note: ${e.message}")
                }
            }
        }
    }
}
