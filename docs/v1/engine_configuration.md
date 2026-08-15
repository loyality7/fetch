# Fetch Engine — Configuration Reference (v1)

Engine behavior is configured via `EngineConfig`:

```kotlin
data class EngineConfig(
    val http: HttpConfig = HttpConfig(),
    val extraction: ExtractionConfig = ExtractionConfig(),
    val index: IndexConfig = IndexConfig(),
    val browser: BrowserConfig = BrowserConfig(),
)
```

## 1. HTTP Configuration (`HttpConfig`)
- `timeout` (Default: `15.seconds`): Socket connect and read timeout.
- `maxResponseBodyBytes` (Default: `10 MB`): Maximum byte limit for HTTP response downloads.
- `defaultBackoffDuration` (Default: `60.seconds`): Default backoff duration applied on `429` or `503` server response without a `Retry-After` header.
- `maxBackoffDuration` (Default: `1.hours`): Hard ceiling for domain backoff windows.

## 2. Index Configuration (`IndexConfig`)
- `maxDatabaseSizeBytes` (Default: `500 MB`): SQLite database storage cap before LRU document pruning triggers.
- `defaultTtl` (Default: `7.days`): Default cache expiration for standard HTML pages.
- `newsTtl` (Default: `1.hours`): Cache expiration for news feeds and RSS items.
- `documentationTtl` (Default: `30.days`): Cache expiration for technical documentation and wiki pages.

## 3. Extraction Configuration (`ExtractionConfig`)
- `maxExtractedChars` (Default: `500,000`): Character limit for extracted markdown output.
- `maxAstDepth` (Default: `50`): Maximum recursion depth for HTML DOM tree parsing (prevents stack overflow attacks).

## 4. Browser Configuration (`BrowserConfig`)
- `timeout` (Default: `15.seconds`): Timeout for SPA JavaScript rendering and DOM settling.
- `blockImages` (Default: `true`): Intercept and block image assets to conserve mobile data.
- `blockMedia` (Default: `true`): Intercept and block audio/video downloads.
