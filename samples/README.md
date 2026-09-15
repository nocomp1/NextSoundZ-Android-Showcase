# Code samples

Nine self-contained samples, each demonstrating one pattern from the production app.

**Read these first if you have five minutes:**

1. **[08-native-audio-bridge](08-native-audio-bridge)** — the most distinctive work. The
   Kotlin↔C++ seam, real-time thread safety, and the lifetime bug that produced three
   crash signatures.
2. **[01-clean-architecture](01-clean-architecture)** — how I isolate a business rule so it
   is testable in milliseconds, and the revenue bug that happens when you don't.
3. **[07-java-to-kotlin](07-java-to-kotlin)** — a before/after with the specific defects the
   rewrite fixed, and an argument for what *not* to migrate.

**Everything else:**

| Sample | Demonstrates |
|---|---|
| [02-compose-mvvm](02-compose-mvvm) | Compose + `StateFlow`, unidirectional data flow, stateful/stateless split, lifecycle-aware collection |
| [03-coroutines-flow](03-coroutines-flow) | Listener→`Flow` migration, back-pressure config for a real-time producer, dispatcher discipline |
| [04-networking-retrofit](04-networking-retrofit) | Retrofit + Moshi, sealed `Result` at the boundary, Hilt module, TLS and logging hygiene |
| [05-room-offline-sync](05-room-offline-sync) | Room as source of truth, cursor-based incremental sync, transactional page commits, `CoroutineWorker` |
| [06-midi-device-io](06-midi-device-io) | Hardware device lifecycle and hot-plug, binary protocol parsing, running status |
| [09-billing-entitlement](09-billing-entitlement) | Offline-tolerant entitlement, Play as source of truth for trial eligibility, offer selection |

## How to read them

Every file is annotated. The comments are the point — they explain *why* the code is shaped
the way it is, usually with reference to a specific production bug that shaped it. If you
only skim the code you will miss most of what these are meant to show.

## Important

These files **do not compile as a project**. They are reading samples: no Gradle wrapper, no
manifest, no resources, no application module. A few reference types that are described but
not included (the proprietary `AudioEngine`, for instance). That is deliberate — see
[What this repo is (and isn't)](../README.md#what-this-repo-is-and-isnt).

Everything here was written specifically for this repository. None of it is production
source, and no real credentials, endpoints, thresholds, product identifiers, or algorithms
appear in it.
