# Fetch Engine SDK — v1 Embedding & Integration Guide

This guide provides instructions for embedding the `fetch` engine into Android applications using the `:core` SDK module.

---

## 1. Add Dependencies

Add the core library dependency to your module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.fetch:core:1.0.0")
}
```

---

## 2. Initialize the Engine

Instantiate `FetchEngine` via the public factory function:

```kotlin
import android.content.Context
import com.fetch.core.sdk.FetchEngine
import com.fetch.core.config.EngineConfig
import com.fetch.core.browser.WebViewBrowserBackend
import com.fetch.core.model.CacheMode

// 1. (Optional) Create Headless WebView Backend for SPAs
val browserBackend = WebViewBrowserBackend(context)

// 2. Instantiate FetchEngine
val engine = FetchEngine.create(
    context = context,
    config = EngineConfig(), // Uses production defaults
    browser = browserBackend
)
```

---

## 3. Perform Engine Operations

### Fetch & Index Web Pages
Fetches a URL, extracts clean Markdown, indexes the document in SQLite FTS5, and returns the result:

```kotlin
val document = engine.fetch(
    rawUrl = "https://example.com/article",
    cacheMode = CacheMode.DEFAULT // Options: DEFAULT, REFRESH, CACHE_ONLY
)

println("Title: ${document.title}")
println("Markdown: ${document.markdown}")
println("Word Count: ${document.metadata.wordCount}")
```

### Full-Text Search (FTS5 BM25)
Search locally indexed documents using FTS5 with BM25 relevance ranking:

```kotlin
val searchResults = engine.search(
    query = "quantum computing",
    limit = 10
)

searchResults.forEach { doc ->
    println("Match: ${doc.title} (${doc.urls.canonical})")
}
```

### Answer Synthesis & Evidence Passages
Synthesize answer passages with citations across indexed content:

```kotlin
val answerResponse = engine.ask(
    query = "What is quantum computing?",
    maxEvidence = 5
)

println("Answer: ${answerResponse.answer}")
answerResponse.evidence.forEach { snippet ->
    println("Citation [${snippet.sourceUrl}]: ${snippet.text}")
}
```

---

## 4. Lifecycle Management & Shutdown

Always close the engine and browser backend when done to prevent resource leaks:

```kotlin
// Clean shutdown releases SQLite connections and destroys WebView sessions
engine.close()
```
