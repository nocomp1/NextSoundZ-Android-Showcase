# Testing strategy

**172 unit-test files, 44 instrumentation-test files**, plus host-compiled C++ tests.

## The principle

Coverage percentage is not a goal. I test by **cost of being wrong**:

- Rules that **cost money** — export allowances, entitlement, trial eligibility, offer selection.
- Rules that **corrupt user data** — clip registration, project serialization, grid math.
- Logic with **non-obvious invariants** — transport state, automation keying, MIDI parsing.

A getter does not get a test. A rounding function that positions clips on a timeline does,
because getting it wrong drifts a user's arrangement and they lose work.

## Layers

| Layer | Tools | What it covers |
|---|---|---|
| **Domain** | JUnit, hand-written fakes | Pure Kotlin rules in `:core:models` / `:core:managers`. Milliseconds to run. |
| **ViewModel** | JUnit, `mockito-kotlin`, `TestCoroutineRule` | Emitted `StateFlow` states given intents, with an injected test dispatcher. |
| **Persistence** | Room in-memory DB | DAO behaviour and migrations against committed schema JSON (`exportSchema = true`). |
| **Native** | Host-compiled C++ tests | Scale mapping, zone behaviour, FX-slot routing, automation. The engine is not a black box to the suite. |
| **UI** | Espresso, Compose UI test | Highest-traffic flows only. |

## A regression test should explain itself

Every test that exists because of a production bug records *why* in its KDoc. The assertion
says what; the comment says what it cost us. From the real suite:

> *Pins the free-export allowance shared by Beat Mode and the DAW. The count is app-wide on
> purpose: before this existed the limit lived only on the song screen, so the DAW exported
> without limit.*

In two years someone will find that test inconvenient and be tempted to change it. That
comment is what stops them re-shipping the bug. It is the most valuable line in the file.

## Design for testability

**Inject the clock.** Anything time-dependent — entitlement windows, quota resets, verification
intervals — takes `clock: () -> Long`. Testing a 14-day expiry by waiting is not a test.

**Inject the dispatcher.** A hardcoded `Dispatchers.IO` makes a suspend function
non-deterministic under test.

**Narrow interfaces owned by the consumer.** `ExportAllowance` depends on a two-method `Counter`,
not on `SharedPreferences`. The fake is four lines and there is no framework involved.

**Prefer fakes to mocks for domain types.** A hand-written fake is readable, refactor-safe,
and does not silently pass when the interface changes. Mockito is reserved for wide
Android-framework interfaces where a fake would be tedious.

## Things I learned the hard way

- **Mockito cannot stub everything.** Final classes, and Kotlin function/property-getter name
  collisions, both fail in ways whose error messages point somewhere unhelpful. Some types
  need a fake or a wrapper interface, and finding that out mid-test is a waste of an hour.
- **Test the *ordering* of analytics, not just that events fire.** A funnel once showed zero
  users — not because events were missing, but because user identity was set after they
  fired. Every event was present and correctly named, and the dashboard was still empty.
- **Device testing is not optional for audio and MIDI.** Latency, input-dispatch lag, and
  `AudioPlaybackCapture` behaviour vary by OS version and by manufacturer. One sampling path
  records silence on Android 10 on two specific devices and works everywhere else. No unit
  test would ever have found that. I keep old hardware for this reason.
