# Fetch Engine (`fetch`)

> Local-First Web Extraction, Search & Retrieval Engine for Android.

`fetch` is an open-source Android library and foreground service that provides web scraping, single-page application (SPA) rendering, text extraction, FTS5 search, and a local REST API for on-device mobile applications.

---

## Overview

`fetch` uses a multi-tiered retrieval pipeline to retrieve and index web pages:

- **Tier 1 (Index Search)**: Local SQLite FTS5 full-text search with BM25 ranking.
- **Tier 2 (HTTP Fetcher)**: HTTP extraction with HTML AST-to-Markdown conversion.
- **Tier 3 (WebView Browser)**: Offscreen Android `WebView` fallback for JavaScript-rendered SPAs with image, font, and media blocking.

---

## Key Features

- **SQLite FTS5 Full-Text Search**: BM25 relevance ranking across locally indexed documents.
- **WebView SPA Rendering**: Renders dynamic JavaScript frameworks with session cleanup and zero memory leaks.
- **Clean Markdown Extraction**: Converts HTML trees into formatted Markdown while stripping ads and scripts.
- **Hydration Fragment Recovery**: Reconstructs prose split across sibling JSON nodes (`__NEXT_DATA__`, `__NUXT__`).
- **SSRF Guard**: Blocks requests targeting private IP ranges (`127.0.0.1`, `10.0.0.0/8`) and caps HTML recursion depth.
- **Per-Domain Backoff**: Respects `Retry-After` HTTP headers and persists host backoff windows.
- **Local REST API Service**: Secure `http://127.0.0.1:8080` API server backed by Android KeyStore AES-256 token encryption.

---

## Documentation (`docs/v1/`)

- **[SDK Embedding Guide](./docs/v1/sdk_embedding_guide.md)** — Integrating `:core` in Android applications.
- **[REST API Specification](./docs/v1/rest_api_specification.md)** — Endpoint schemas (`/v1/search`, `/v1/open`, `/v1/extract`, `/v1/find`, `/v1/add`, `/v1/ask`, `/v1/health`).
- **[Error Codes Reference](./docs/v1/error_codes.md)** — List of error codes and remediations.
- **[Engine Configuration](./docs/v1/engine_configuration.md)** — HTTP timeouts, database size limits, and browser settings.
- **[Mobile Testing Guide](./docs/v1/mobile_testing_guide.md)** — Running instrumented integration tests on physical Android devices.
- **[Verification Report](./docs/v1/readiness_and_testing_verification.md)** — On-device test results and subsystem status.

---

## Quick Start

```kotlin
import com.fetch.core.sdk.FetchEngine
import com.fetch.core.browser.WebViewBrowserBackend
import com.fetch.core.model.CacheMode

// 1. Initialize Browser Backend for SPA support
val browser = WebViewBrowserBackend(context)

// 2. Instantiate Engine
val engine = FetchEngine.create(context, browser = browser)

// 3. Fetch & Index Web Content
val document = engine.fetch("https://example.com/article", cacheMode = CacheMode.DEFAULT)
println("Title: ${document.title}")
println("Markdown:\n${document.markdown}")

// 4. Search Local Index with BM25 Ranking
val results = engine.search(query = "quantum computing", limit = 5)

// 5. Shutdown
engine.close()
```

---

## License

Licensed under the [Apache License 2.0](./LICENSE). Third-party notices are listed in [NOTICES.md](./NOTICES.md).
