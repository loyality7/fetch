package com.fetch.core.net

import com.fetch.core.config.HttpConfig
import com.fetch.core.error.EngineException
import com.fetch.core.error.ErrorCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Tier 1. Plain HTTP.
 *
 * Deliberately thin: OkHttp already does pooling, HTTP/2, redirects, and
 * decompression. The value added here is the response cap and the mapping to
 * the engine's error model.
 */
public class HttpFetcher(
    private val config: HttpConfig,
    private val guard: SsrfGuard,
    private val client: OkHttpClient = defaultClient(config),
) {

    public suspend fun fetch(url: String, headers: Map<String, String> = emptyMap()): HttpResult =
        withContext(Dispatchers.IO) {
            guard.check(url)

            val request = Request.Builder()
                .url(url)
                .header("User-Agent", config.userAgent)
                .header("Accept-Encoding", "gzip")
                .apply { headers.forEach { (k, v) -> header(k, v) } }
                .build()

            val call = client.newCall(request)
            try {
                call.execute().use { response ->
                    val body = response.body ?: throw EngineException(ErrorCode.HTTP_ERROR, "Empty response")

                    if (body.contentLength() > config.maxResponseBytes) {
                        throw EngineException(ErrorCode.RESPONSE_TOO_LARGE, "Response exceeds limit")
                    }

                    // Buffer one byte past the cap: a body that arrives without a
                    // declared length, or lies about it, is caught here rather
                    // than after it has already been read into memory.
                    val source = body.source()
                    source.request(config.maxResponseBytes + 1)
                    if (source.buffer.size > config.maxResponseBytes) {
                        throw EngineException(ErrorCode.RESPONSE_TOO_LARGE, "Response exceeds limit")
                    }
                    val bytes = source.readByteArray()

                    // The final URL after redirects — the guard must see it too.
                    val finalUrl = response.request.url.toString()
                    if (finalUrl != url) guard.check(finalUrl)

                    HttpResult(
                        url = url,
                        finalUrl = finalUrl,
                        statusCode = response.code,
                        contentType = response.header("Content-Type"),
                        etag = response.header("ETag"),
                        lastModified = response.header("Last-Modified"),
                        body = bytes.toString(charsetFor(response.header("Content-Type"))),
                        bytes = bytes.size.toLong(),
                    )
                }
            } catch (e: IOException) {
                throw EngineException(ErrorCode.NETWORK_UNAVAILABLE, "Request failed", e)
            }
        }

    private fun charsetFor(contentType: String?): java.nio.charset.Charset {
        val declared = contentType?.substringAfter("charset=", "")?.trim()?.takeIf { it.isNotEmpty() }
        return runCatching { java.nio.charset.Charset.forName(declared) }
            .getOrDefault(Charsets.UTF_8)
    }

    public companion object {
        public fun defaultClient(config: HttpConfig): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(config.connectTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .readTimeout(config.readTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .callTimeout(config.totalTimeout.inWholeMilliseconds, TimeUnit.MILLISECONDS)
            .followRedirects(true)
            .build()
    }
}

public data class HttpResult(
    val url: String,
    val finalUrl: String,
    val statusCode: Int,
    val contentType: String?,
    val etag: String?,
    val lastModified: String?,
    val body: String,
    val bytes: Long,
)
