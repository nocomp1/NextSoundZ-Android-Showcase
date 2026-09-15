# Java-to-Kotlin modernization

The production codebase is **1,137 Kotlin files to 38 Java files** — 98.3% Kotlin by file
count. It did not start that way.

## The approach

**Convert on contact, not in bulk.** A file was converted when it was being changed for
feature or bug work anyway. That kept every conversion inside a change that was already
being tested and reviewed, instead of producing one enormous untestable diff.

**Treat the auto-conversion as a first draft.** The IDE converter is good at syntax and
blind to design. Its output is a Kotlin file with Java's assumptions intact: `!!` wherever
it could not prove non-nullity, `var` for everything, platform types at every framework
boundary. The real work starts after it runs. `Before_TempoTrack.java` and
`After_TempoTrack.kt` in this folder show the gap between what the converter gives you and
what the conversion is actually worth.

**Replace idioms, don't transliterate them.**

| Java pattern | What it became |
|---|---|
| Listener interface + register/unregister | `SharedFlow` (see [samples/03-coroutines-flow](../03-coroutines-flow)) |
| `AsyncTask`, raw `Thread` | `suspend` functions with injected dispatchers |
| Static utility class | `object`, or extension functions on the receiver |
| Mutable bean + getters/setters | `data class` |
| Nullable return standing in for a default | Non-null return with a real default |
| `Collections.sort` on every mutation | An invariant maintained by the type |

## What I deliberately did not convert

The remaining Java is almost entirely one subsystem: an implementation of the **Standard
MIDI File** binary specification — event types, meta events, variable-length quantities,
track chunk parsing.

It stays in Java on purpose:

- It implements a **frozen external spec**. It will not gain features, so the maintenance
  argument for converting it is close to zero.
- It is **pure computation over bytes** — no nullability ambiguity, no threading, no Android
  framework surface. The defect classes a Kotlin conversion protects against are not present.
- It is **exercised on every project load and export**, so a subtle regression would be
  expensive and might not surface immediately.
- Kotlin/Java interop is seamless, so there is no ergonomic cost to callers.

Converting it would be several thousand lines of churn, real regression risk, and no
measurable benefit. **Deciding what not to migrate is part of doing a migration well** —
a 100% Kotlin badge is not a business outcome.
