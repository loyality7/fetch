# fetch

An on-device knowledge engine for Android.

> **Under development.** Nothing here is usable yet. The API will change.

## What it will do

Search the live web, extract clean readable content, and keep everything it
sees in a local index — along with anything you add yourself. Agents query it
over a loopback HTTP API or an in-process Kotlin SDK, online or offline.

No per-request cost. No mandatory API key. Nothing leaves the device.

## How it works

The index is the product. Web access is how it grows.

A query checks the local index first. On a miss, several search sources are
queried in parallel and their results fused, pages are fetched through the
cheapest path that works, extracted, and written back to the index. The same
query later is answered locally, offline.

Fetching escalates only on evidence — thin content or a client-rendered shell —
never on assumptions about a domain, and the path that worked is remembered per
domain.

## Modules

| Module | Contents |
|---|---|
| `core` | Models, HTTP, extraction, index, discovery, router |
| `service` | Loopback HTTP API |
| `demo` | Sample application |

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

Not yet selected.
