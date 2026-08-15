# `fetch` — Developer Integration, REST API & Configuration Reference

`fetch` is an open-source, local-first web extraction, indexing, and retrieval engine for Android. It provides structured text extraction, fast FTS5 full-text search with BM25 ranking, dynamic content TTL caching, per-domain backoff, and a headless `WebView` browser fallback for single-page applications (SPAs).

---

## 1. SDK Embedding Guide

### Add Dependency
Include the `:core` and optional `:service` modules in your `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.fetch:core:1.0.0")
    implementation("com.fetch:service:1.0.0") // Optional: for HTTP API Server
}
```

### Initializing the Engine
Instantiate the engine via the public factory function `FetchEngine.create(...)`:

```kotlin
import android.content.Context
import com.fetch.core.sdk.FetchEngine
import com.fetch.core.config.EngineConfig
import com.fetch.core.browser.WebViewBrowserBackend
import com.fetch.core.model.CacheMode

// 1. (Optional) Initialize Browser Backend for SPAs
val browserBackend = WebViewBrowserBackend(context)

// 2. Instantiate FetchEngine
val engine = FetchEngine.create(
    context = context,
    config = EngineConfig(), // Default configuration
    browser = browserBackend
)

// 3. Perform Engine Operations
val document = engine.fetch("https://example.com/article", cacheMode = CacheMode.DEFAULT)
println("Title: ${document.title}")
println("Markdown: ${document.markdown}")

// 4. Clean Shutdown
engine.close()
```

---

## 2. Local REST API Reference (v1)

When running `LocalApiServer` (via `FetchForegroundService`), the API is available locally at `http://127.0.0.1:8080` (or configured port). All endpoints except `/v1/health` require the `Authorization: Bearer <TOKEN>` header.

### Endpoints

#### `POST /v1/search`
Queries the indexed document corpus using FTS5 full-text search.
- **Request Body**: `{"query": "quantum mechanics", "limit": 10}`
- **Response**: `200 OK` with JSON array of matching `DocumentDto` objects.

#### `POST /v1/open`
Fetches a URL, extracts content, indexes it, and returns the parsed `Document`.
- **Request Body**: `{"url": "https://example.com/article", "cacheMode": "DEFAULT"}`
- **Response**: `200 OK` returning `DocumentDto`.

#### `POST /v1/extract`
Parses raw HTML string into clean markdown without performing network calls.
- **Request Body**: `{"html": "<html>...</html>", "baseUrl": "https://example.com"}`
- **Response**: `200 OK` returning `ExtractedDto` (`text`, `markdown`, `title`, `metadata`).

#### `POST /v1/find`
Retrieves paragraph-level evidence passages matching a query.
- **Request Body**: `{"url": "https://example.com/article", "query": "quantum", "maxPassages": 3}`
- **Response**: `200 OK` returning `List<EvidenceDto>`.

#### `POST /v1/add`
Directly indexes custom text or HTML into the search store.
- **Request Body**: `{"content": "Prose content...", "title": "Custom Document", "url": "https://example.com/custom"}`
- **Response**: `200 OK` returning indexed `DocumentDto`.

#### `POST /v1/ask`
Performs intent classification, web discovery, passage extraction, and synthesizes an answer with citations.
- **Request Body**: `{"query": "What is quantum computing?", "maxEvidence": 5}`
- **Response**: `200 OK` returning `AskResponseDto` (`answer`, `evidence`, `query`, `vertical`).

#### `GET /v1/health`
Returns real-time operational health and lock-free telemetry metrics.
- **Auth**: Unauthenticated.
- **Response**: `200 OK` returning `HealthDto` containing status, document count, storage usage, and operational metrics (`total_requests`, `index_hits`, `escalations_to_browser`, `avg_fetch_latency_ms`, etc.).

---

## 3. Error Codes Reference

| Error Code | HTTP Status | Description |
| :--- | :--- | :--- |
| `UNAUTHORIZED` | `401` | Missing or invalid API bearer token |
| `RESPONSE_TOO_LARGE` | `413` | Request payload, query, or URL exceeded length limits |
| `RATE_LIMITED` | `429` | Domain is currently in backoff window following a `429` or `503` server response |
| `SSRF_BLOCKED` | `400` | URL targets internal/private IP ranges (`127.0.0.1`, `10.0.0.0/8`, etc.) or unregistered schemes |
| `UNSUPPORTED_CONTENT` | `415` | Content-Type is not supported for extraction (e.g. binary media) |
| `INDEX_ONLY` | `404` | Document not found in local index under `CACHE_ONLY` mode |
| `HTTP_ERROR` | `502` | Upstream HTTP fetch failed with a non-2xx status code |
| `BROWSER_FAILED` | `500` | Headless WebView rendering failed or timed out |

---

## 4. Configuration Reference

```kotlin
data class EngineConfig(
    val http: HttpConfig = HttpConfig(),
    val extraction: ExtractionConfig = ExtractionConfig(),
    val index: IndexConfig = IndexConfig(),
    val browser: BrowserConfig = BrowserConfig(),
)
```

- **`http.timeout`**: HTTP request timeout (Default: `15.seconds`).
- **`http.maxResponseBodyBytes`**: Max download size per HTTP request (Default: `10 MB`).
- **`index.maxDatabaseSizeBytes`**: Database storage ceiling before LRU pruning triggers (Default: `500 MB`).
- **`extraction.maxExtractedChars`**: Maximum character limit for extracted markdown (Default: `500,000`).
- **`browser.timeout`**: Maximum wait time for SPA JavaScript rendering (Default: `15.seconds`).

---

## 5. Non-Goals

1. **Not a General Web Browser**: `fetch` does not aim to replace full browser engines or render interactive DOM UIs.
2. **Not a Heavy Vector DB**: Uses FTS5 BM25 keyword search and local intent rules instead of heavy neural vector embeddings to maintain ultra-low battery and memory footprint on mobile devices.
