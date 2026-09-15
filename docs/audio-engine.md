# Audio engine integration

~63,000 lines of C++ behind a 126-method JNI boundary. This document explains the
constraints that shape everything above it.

## The constraint

The audio device calls back on a dedicated high-priority thread asking for the next buffer
of samples. At 48kHz with a 256-frame buffer, that callback fires every ~5.3ms, and it must
return in less than that. Miss the deadline and the user hears a click, a dropout, or a
stutter — instantly, and far more noticeably than any visual jank.

Inside that callback:

- **No allocation.** `malloc` can block on a lock held by another thread.
- **No locks.** Priority inversion against a lower-priority thread is unbounded.
- **No JNI, no JVM.** Attaching can block, and the GC can suspend the thread.
- **No file, no network, no logging.**

Almost every architectural decision in this app follows from that list.

## Division of responsibility

| Kotlin | C++ / JUCE |
|---|---|
| Project state, persistence, networking | The real-time audio callback |
| UI, gestures, rendering | Sample playback, mixing, routing |
| Transport *intent* (play/stop/seek/tempo) | Effects chain, parameter automation |
| Serializing state for native | Offline (faster-than-real-time) export |
| Scheduling, lifecycle | Time-stretch / pitch-shift (Rubber Band), onset detection (aubio) |

Kotlin decides *what* should happen. C++ decides *when each sample is produced*.

## The boundary contract

### Downward: cheap, coarse, idempotent

Kotlin → native calls are plain, fast, and non-blocking. Two rules:

**Coarse-grained.** A JNI transition costs far more than the work in a typical setter.
Rather than crossing the boundary per note, the app serializes an entire arrangement and
pushes it once. Chatty boundaries starve the audio thread.

**Idempotent and memoized.** A real bug: a volume change re-pushed the whole project, and
native rebuilt every synth preset unconditionally — re-decoding sample data and briefly
dropping audio. Any volume nudge produced an audible glitch. The fix was a per-track memo
on the *Kotlin* side so an unchanged preset is never re-applied. The defect was native; the
correct fix was above the boundary.

### Upward: pull, never push

**Native never calls into the JVM from the audio callback.** Instead:

```
 audio thread ──writes──▶ lock-free ring buffer
                                 │
 JVM scheduled clock ──drains────┘──▶ StateFlow ──▶ UI
```

The audio thread does a bounded, allocation-free write. A scheduled task on the JVM side
drains at UI cadence and emits into a `Flow`. The playhead in the UI is therefore always
the engine's real position, and the real-time thread never touches the JVM.

See [samples/08-native-audio-bridge](../samples/08-native-audio-bridge) for the
implementation of both directions.

## Lifetime and teardown

The most expensive class of bug at this boundary, by a wide margin.

Activity teardown races audio shutdown. A call arriving after the engine is destroyed
dereferences freed memory and crashes natively, with a stack trace that names none of your
code — which is why these are so hard to diagnose from Play Vitals.

**One use-after-free on a voice slot was producing three unrelated-looking crash
signatures.** Three separate Vitals entries, three different apparent causes, one lifetime
bug. Fixing the ownership of that single object resolved all three.

The pattern now:

- The engine is owned by a `shared_ptr`, published behind an atomic ready flag.
- Every entry point takes a strong reference for the duration of its call, so a call in
  flight during teardown holds the engine alive rather than reading a dangling pointer.
- Teardown closes the gate *before* touching the engine, stops the callback, and waits for
  the current buffer.
- The Kotlin side has a matching `@Volatile` ready flag so most calls never even reach JNI
  after shutdown.

## Other production reliability work

- **Null-`engine` guards** for calls arriving after teardown on paths the flag did not cover.
- **Weak references for sound-instance callbacks**, so a completed one-shot cannot retain a
  destroyed owner.
- **Synth sample decoding moved off the main thread** — it was producing ANRs on low-end
  devices, where decoding a large multi-sample preset took long enough to trip the watchdog.
- **Recycled-bitmap guards** in waveform rendering, where the drawing path could outlive the
  bitmap it was handed.

## Build

CMake + NDK r29, ABIs `armeabi-v7a` / `arm64-v8a` / `x86` / `x86_64` for debug and ARM-only
for release. Third-party native dependencies (Rubber Band, FFTW) are built once into a
per-ABI cache rather than rebuilt per CI run — without that, native compilation dominates
pipeline time.

Release builds ship with `debugSymbolLevel 'full'` so native crashes arrive in Play Vitals
symbolicated. Debugging a stripped native stack trace from a user's device is close to
impossible; this one build setting is the difference between a fixable crash report and a
hex dump.

## What is not in this repository

The DSP and engine implementation are proprietary and are not published here: the JUCE
graph construction, mixing and routing, the effects implementations, synthesis, the
time-stretch integration, onset detection and auto-chop, and the offline export renderer.

The **boundary** — marshalling, lifetimes, threading, and the real-time safety rules — is
shown in full, because that is the transferable engineering.
