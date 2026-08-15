# Fetch Engine — Version 1.0.0 (v1) Specification & Verification Report

## Executive Summary & Readiness Declaration

The `fetch` engine is a local-first, privacy-preserving web extraction, indexing, and search engine engineered specifically for Android mobile hardware. Version 1.0.0 (v1) is **fully implemented, verified on physical hardware, and ready for production deployment.**

- **Repository**: `https://github.com/loyality7/fetch`
- **Target OS / Environment**: Android 8.0+ (API 26 to API 35+)
- **Physical Test Device**: Xiaomi 22041219PI (Android 14)
- **Real Device Test Pass Rate**: **100% (39 / 39 instrumented tests passed)**
- **Open GitHub Issues Remaining**: **0 (All 24/24 issues resolved and closed)**
- **CI Build Status**: GitHub Actions Green

---

## Architecture & Subsystem Readiness Matrix

| Subsystem | Readiness | Status & Verification Details |
| :--- | :--- | :--- |
| **Local Search Store (`:core:index`)** | **PRODUCTION READY** | SQLite FTS5 full-text search with BM25 relevance ranking. Dynamic content TTL caching (News 1h, Docs 30d, Search 15m). Dynamic LRU database size cap (500MB ceiling) verified via 1,000 document load tests. |
| **Extraction & State Recovery (`:core:extract`)** | **PRODUCTION READY** | DOM AST to clean Markdown converter. Stack-overflow safe recursion limits (cap = 50). Embedded JSON state payload recovery (`__NEXT_DATA__`, `__NUXT__`) with adjacent prose fragment joining. |
| **Fetch Router & Domain Throttling (`:core:router`)** | **PRODUCTION READY** | Content-hash deduplication (`etag`, `lastModified`) with `304 Not Modified` conditional HTTP checks. Per-domain backoff persistence (`Retry-After` header extraction and fast-fail rate-limiting). |
| **Intent Classification & Routing (`:core:discovery`)** | **PRODUCTION READY** | Zero-latency local `QueryClassifier` categorizing queries (`CODE`, `DOCS`, `NEWS`, `PAPERS`, `GENERAL`) and routing to specialized search sources with general fan-out fallback. |
| **Browser Tier (`:core:browser`)** | **PRODUCTION READY** | Headless Android `WebViewBrowserBackend` executing offscreen on Main Looper. Request interception blocking images/media, JavaScript DOM evaluation for SPAs, and full WebStorage/cookie lifecycle cleanup. |
| **Service Hosting & Security (`:service`)** | **PRODUCTION READY** | `FetchForegroundService` running `LocalApiServer` on `127.0.0.1`. AES-256 GCM token storage backed by Android KeyStore. Input length and payload limits (`2MB` body max, `1,000` char query max, `2,048` char URL max). |
| **Operational Telemetry (`:core:metrics`)** | **PRODUCTION READY** | Lock-free atomic `EngineMetrics` tracking request counts, index hits, browser escalations, downloaded bytes, renderer crashes, cancellations, and stage latencies. Exposed via `/v1/health`. |

---

## On-Device Testing & Verification Summary

The complete test suite was verified on a physical Xiaomi 22041219PI running Android 14.

### 1. `:core` Module Test Suite (34/34 Passed)
- **FTS5 & BM25 Ranking**: Verified fast full-text search and BM25 relevance scoring over SQLite FTS5 virtual tables.
- **1,000-Document Load & Latency**: Verified bulk indexing of 1,000 documents under 50ms search latency, LRU pruning, and heap memory stability (< 35MB growth).
- **Passage Selection**: Verified term-rarity (Inverse Document Frequency) and proximity span weighting prioritizing high-relevance paragraphs.
- **Embedded State Fragment Joining**: Verified mid-sentence prose fragment reconstruction across JSON hydration nodes.
- **Adversarial Hardening**: Verified stack recursion caps against deeply-nested HTML attacks and rejection of unauthorized schemes (`file://`, `gopher://`).
- **Cancellation Propagation**: Verified coroutine cancellation cleanup across active WebView sessions without memory or handle leaks.
- **Routing Granularity**: Verified host-level domain keying tradeoffs vs path-prefix keying.

### 2. `:service` Module Test Suite (5/5 Passed)
- **Token Management**: Verified AES-256 GCM token generation, Android KeyStore encryption, retrieval, and token rotation.
- **Local HTTP Loopback Server**: Verified `127.0.0.1` binding, `401 Unauthorized` header rejection, `413 Response Too Large` payload enforcement, and full v1 endpoint responses (`/v1/search`, `/v1/open`, `/v1/extract`, `/v1/find`, `/v1/add`, `/v1/ask`, `/v1/health`).
