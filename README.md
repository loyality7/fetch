# fetch

An on-device knowledge engine for Android.

`fetch` searches the live web, extracts clean structured content, and indexes
everything it sees — plus anything you add yourself — into a local database. AI
agents query it over a loopback HTTP API or an in-process Kotlin SDK, online or
offline, with no per-request cost and no mandatory API key.

> Early development. The API is not stable.

## Design

The index is the product. Web access is how it grows.

```
        consumer  (agent, CLI, another app)
                 |  127.0.0.1 + bearer token
      +----------v----------+
      |     engine core     |
      +----------+----------+
                 |
     +-----------+-----------+
     |           |           |
  index       ingest     discovery
  SQLite      fetch      pluggable
  + FTS5      ladder     sources
                 |
     +-----------+-----------+
     |           |           |
  cache        HTTP       browser
```

A query checks the index first. On a miss, discovery fans out to several
sources in parallel, results are fused by reciprocal rank, pages are fetched
through the cheapest tier that works, extracted, and written back to the index.
The next identical query is answered locally, offline, in milliseconds.

## Modules

| Module | Contents |
|---|---|
| `core` | Models, HTTP tier, extraction, index, discovery, fetch router |
| `service` | Loopback HTTP API and Android service host |
| `demo` | Sample application |

## Fetch ladder

Escalation is driven by what a response actually contains — thin content, SPA
shell markers, anti-bot signals — never by assumptions about a domain. The tier
that succeeded is recorded per domain and tried first next time.

| Tier | Path |
|---|---|
| 0 | Local index |
| 1 | HTTP |
| 2 | TLS-impersonated HTTP *(planned)* |
| 3 | Browser |

The browser tier is an interface with no bundled implementation. An HTTP-only
build is a fully supported configuration.

## Search sources

Sources are plugins and are treated as disposable — any one can fail without
affecting a query. Sources backed by published endpoints are enabled by
default; sources that parse HTML pages are opt-in.

## Status

| Area | State |
|---|---|
| Models, errors, configuration | skeleton |
| URL normalization, SSRF guard | skeleton |
| HTTP tier | skeleton |
| Extraction, markdown | skeleton |
| Index schema | skeleton |
| Discovery, rank fusion | skeleton |
| Fetch router | skeleton |
| Loopback API | skeleton |
| Browser tier | interface only |

## Requirements

- Android API 26+
- JDK 17
- Gradle 8.x

## Building

```
gradle wrapper
./gradlew :core:assembleDebug
```

## License

Not yet selected. All dependency licenses are audited before first release.
