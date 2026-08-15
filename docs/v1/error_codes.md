# Fetch Engine — Error Codes Reference (v1)

When an operation fails, the SDK throws an `EngineException` or the REST API returns a JSON error response containing an `errorCode` and HTTP status code.

| Error Code | HTTP Status | Root Cause & Remediation |
| :--- | :--- | :--- |
| `UNAUTHORIZED` | `401` | **Missing or invalid API token.** Ensure the request includes `Authorization: Bearer <TOKEN>`. |
| `RESPONSE_TOO_LARGE` | `413` | **Resource limit exceeded.** Request body > `2 MB`, query length > `1,000` chars, or URL length > `2,048` chars. |
| `RATE_LIMITED` | `429` | **Domain throttled.** Target domain is in a backoff window following a prior `429` or `503` response. Wait for `backoffUntil` timestamp. |
| `SSRF_BLOCKED` | `400` | **Security policy violation.** Target URL resolves to a local/private IP address (`127.0.0.1`, `10.0.0.0/8`, `192.168.0.0/16`) or uses an unsupported scheme (`file://`). |
| `UNSUPPORTED_CONTENT` | `415` | **Unextractable Content-Type.** Server returned binary media (image/png, video/mp4, pdf) that cannot be parsed into prose. |
| `INDEX_ONLY` | `404` | **Cache miss in index-only mode.** Requested URL was not found in local index under `CacheMode.CACHE_ONLY`. |
| `HTTP_ERROR` | `502` | **Upstream network failure.** Remote server returned an HTTP error status (404, 500, etc.). |
| `BROWSER_FAILED` | `500` | **WebView rendering error.** Headless browser tier failed to load or settle within the timeout window. |
