# Fetch Engine (`fetch`)

> Local-First, Privacy-Preserving Web Extraction, Search & Retrieval Engine for Android.

`fetch` is an open-source, lightweight Android library and foreground service built to enable on-device web scraping, single-page application (SPA) rendering, structured text extraction, fast FTS5 full-text search, and local REST API hosting directly on mobile devices.

---

## Why We Built `fetch`

Modern mobile applications and AI agents running on-device face significant challenges when fetching and reading web content:

1. **High Battery & Data Costs**: Desktop web scraping tools (like Puppeteer or Playwright) cannot run efficiently on Android devices due to heavy memory footprints and CPU throttling.
2. **Dynamic SPA Hydration**: Standard HTTP clients (like OkHttp) fail to capture dynamic content rendered by modern JavaScript frameworks (`Next.js`, `Nuxt`, `React`).
3. **Privacy & Offline Resilience**: Relying on external cloud scraping APIs exposes user network traffic to third parties, requires constant internet connection, and incurs per-request API costs.

**`fetch` resolves these challenges by providing a unified, multi-tiered engine:**
- **Tier 1 (Instant Index Lookup)**: Sub-millisecond local SQLite FTS5 search.
- **Tier 2 (Lightweight HTTP Fetcher)**: Fast, low-bandwidth HTTP extraction with AST-to-Markdown conversion.
- **Tier 3 (Offscreen WebView Browser)**: Headless, in-process Android `WebView` fallback for JavaScript-rendered SPAs with automatic asset blocking (images, fonts, media).

---

## Key Features

- **SQLite FTS5 Full-Text Search**: Fast BM25 relevance ranking across locally indexed documents.
- **Headless WebView SPA Support**: Renders client-side dynamic frameworks with automatic session lifecycle cleanup and zero memory leaks.
- **Clean Markdown Extraction**: Converts HTML trees into clean, formatted Markdown while stripping ads, scripts, and clutter.
- **Hydration Fragment Recovery**: Reconstructs prose split across sibling JSON nodes (`__NEXT_DATA__`, `__NUXT__`).
- **SSRF Guard & Adversarial Hardening**: Blocks requests targeting private IP ranges (`127.0.0.1`, `10.0.0.0/8`) and prevents stack-overflow attacks via HTML recursion depth caps.
- **Per-Domain Backoff & Rate-Limiting**: Respects `Retry-After` HTTP headers and persists host-level backoff windows.
- **Local REST API Service**: Exposes a secure `http://127.0.0.1:8080` local API server backed by Android KeyStore AES-256 token encryption.

---

## Documentation Index (`docs/v1/`)

Detailed guides are modularized inside the [`docs/v1/`](./docs/v1/) directory:

- **[SDK Embedding Guide](./docs/v1/sdk_embedding_guide.md)** — Step-by-step instructions for embedding `:core` in Android apps.
- **[REST API Specification](./docs/v1/rest_api_specification.md)** — Full endpoint schemas (`/v1/search`, `/v1/open`, `/v1/extract`, `/v1/find`, `/v1/add`, `/v1/ask`, `/v1/health`).
- **[Error Codes Reference](./docs/v1/error_codes.md)** — Comprehensive list of error codes, HTTP statuses, and remediations.
- **[Engine Configuration](./docs/v1/engine_configuration.md)** — Customizing HTTP timeouts, database size limits, and browser settings.
- **[Mobile Testing Guide](./docs/v1/mobile_testing_guide.md)** — Instructions for running instrumented integration tests live on physical Android phones.
- **[Verification Report](./docs/v1/readiness_and_testing_verification.md)** — On-device test results and subsystem readiness matrix.

---

## Quick Start (SDK Usage)

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

// 5. Clean Shutdown
engine.close()
```

---

## Licence

This project is open-source under the [Apache License 2.0](./LICENSE). Third-party dependency notices are recorded in [NOTICES.md](./NOTICES.md).
