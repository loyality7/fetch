# Fetch Local REST API — v1 Endpoint & Schema Specification

The `fetch` service module runs a local HTTP API server at `http://127.0.0.1:8080` hosted inside an Android Foreground Service (`FetchForegroundService`).

All requests except `/v1/health` require an `Authorization: Bearer <TOKEN>` header.

---

## Headers

```http
Authorization: Bearer <YOUR_API_TOKEN>
Content-Type: application/json
```

---

## Endpoints

### 1. `POST /v1/search`
Queries the local document store using FTS5 full-text search with BM25 ranking.

#### Request Body
```json
{
  "query": "quantum computing",
  "limit": 10
}
```

#### Response (`200 OK`)
```json
[
  {
    "id": "doc_123",
    "title": "Introduction to Quantum Computing",
    "requestedUrl": "https://example.com/quantum",
    "canonicalUrl": "https://example.com/quantum",
    "text": "Full plain text content...",
    "markdown": "# Introduction to Quantum Computing\n...",
    "wordCount": 450,
    "fetchedAt": 1723720000000
  }
]
```

---

### 2. `POST /v1/open`
Fetches a URL live or from cache, extracts content, indexes it, and returns the parsed document.

#### Request Body
```json
{
  "url": "https://example.com/article",
  "cacheMode": "DEFAULT"
}
```

#### Response (`200 OK`)
Returns `DocumentDto` (same structure as search result object above).

---

### 3. `POST /v1/extract`
Parses a raw HTML string into clean Markdown without making network calls.

#### Request Body
```json
{
  "html": "<html><body><h1>Title</h1><p>Prose text...</p></body></html>",
  "baseUrl": "https://example.com"
}
```

#### Response (`200 OK`)
```json
{
  "title": "Title",
  "text": "Title\nProse text...",
  "markdown": "# Title\nProse text...",
  "wordCount": 3
}
```

---

### 4. `POST /v1/find`
Extracts relevant paragraph passages matching a query from a specific URL.

#### Request Body
```json
{
  "url": "https://example.com/article",
  "query": "quantum mechanics",
  "maxPassages": 3
}
```

#### Response (`200 OK`)
```json
[
  {
    "text": "Quantum mechanics is a fundamental theory in physics...",
    "score": 4.85,
    "sourceUrl": "https://example.com/article"
  }
]
```

---

### 5. `POST /v1/add`
Directly indexes custom raw text or HTML content into the search index.

#### Request Body
```json
{
  "content": "Custom document text content...",
  "title": "Custom Document",
  "url": "https://example.com/custom"
}
```

#### Response (`200 OK`)
Returns indexed `DocumentDto`.

---

### 6. `POST /v1/ask`
Classifies query intent, discovers relevant sources, extracts evidence passages, and synthesizes an answer with citations.

#### Request Body
```json
{
  "query": "What is quantum computing?",
  "maxEvidence": 5
}
```

#### Response (`200 OK`)
```json
{
  "query": "What is quantum computing?",
  "vertical": "GENERAL",
  "answer": "Quantum computing is a rapidly-emerging technology that harnesses the laws of quantum mechanics to solve complex problems...",
  "evidence": [
    {
      "text": "Quantum computing harnesses the laws of quantum mechanics...",
      "score": 5.2,
      "sourceUrl": "https://example.com/quantum"
    }
  ]
}
```

---

### 7. `GET /v1/health`
Returns real-time server health and operational telemetry metrics. (Unauthenticated)

#### Response (`200 OK`)
```json
{
  "status": "OK",
  "totalDocuments": 150,
  "storageSizeBytes": 4521000,
  "metrics": {
    "totalRequests": 42,
    "indexHits": 30,
    "browserEscalations": 2,
    "downloadedBytes": 1048576,
    "rendererCrashes": 0,
    "cancellations": 0,
    "avgFetchLatencyMs": 120.5
  }
}
```
