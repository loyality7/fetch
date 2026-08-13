package com.fetch.core.error

/**
 * Every failure the engine can produce, as a closed set.
 *
 * Callers switch on the code. Messages are for humans and are never parsed.
 * Nothing here ever carries a stack trace, filesystem path, or secret across
 * the API boundary.
 */
public enum class ErrorCode(public val retryable: Boolean) {
    NETWORK_UNAVAILABLE(retryable = true),
    DNS_FAILURE(retryable = true),
    TLS_FAILURE(retryable = false),
    HTTP_ERROR(retryable = true),
    REDIRECT_LIMIT(retryable = false),
    TIMEOUT(retryable = true),
    RESPONSE_TOO_LARGE(retryable = false),
    UNSUPPORTED_CONTENT(retryable = false),
    EXTRACTION_FAILED(retryable = false),

    /** Page needs JavaScript and no browser tier was available or permitted. */
    JS_REQUIRED(retryable = false),
    BROWSER_UNAVAILABLE(retryable = false),
    BROWSER_CRASHED(retryable = true),

    MEMORY_LIMIT(retryable = true),
    RATE_LIMITED(retryable = true),
    BLOCKED(retryable = false),
    SSRF_BLOCKED(retryable = false),

    /** CACHE_ONLY was requested and the index had nothing. */
    INDEX_ONLY(retryable = false),

    CANCELLED(retryable = false),
    INTERNAL(retryable = false),
}

public class EngineException(
    public val code: ErrorCode,
    message: String,
    cause: Throwable? = null,
    /** Correlates with the X-Request-ID served over the local API. */
    public val requestId: String? = null,
) : Exception(message, cause) {

    public val retryable: Boolean get() = code.retryable

    override fun toString(): String = "EngineException($code): $message"
}
