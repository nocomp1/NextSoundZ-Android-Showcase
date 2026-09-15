# Architecture

## Why 37 modules

The module count is a consequence of two forces: build times on a codebase with a large
native component, and a hard need to keep business rules out of UI code.

**Build time.** A full native rebuild is expensive. Module boundaries mean a change to a
Compose screen does not invalidate the domain layer, and nothing in Kotlin invalidates the
C++ build. Gradle's incremental and parallel execution only helps if there are boundaries
to be incremental across.

**Enforcement.** A layering rule that lives in a style guide is a suggestion. A layering
rule expressed as a Gradle dependency is a compile error. `:core:models` cannot accidentally
import Android because Android is not on its classpath.

## Dependency direction

```
feature modules  (:drums :sampler :synth :daw :sequencer :mixing :promo :checkout)
        │  depend downward only — never on each other
        ▼
presentation     (:core:viewModel  :core:design)
        ▼
domain           (:core:managers  :core:models  :core:engine)
        ▼
data             (:core:repository  :core:networking  :dataSource  :core:analytics)
        ▼
native           (JNI bridge → JUCE audio graph)
```

**Feature modules never depend on each other.** Two instrument screens that need to share
behaviour share it through the domain layer, not by one importing the other. Without this
rule a multi-module project degenerates into a single module with extra build files.

## Layer responsibilities

### `:core:models` — pure Kotlin

Domain types with no Android imports. Projects, patterns, clips, tracks, notes,
automation. Testable with plain JUnit, no Robolectric, no instrumentation. This is the
module I am most disciplined about: one Android import here and the fast test suite stops
being fast.

### `:core:managers` — the domain layer

Where the actual rules live. Transport state, pattern editing, quota and entitlement
enforcement, MIDI routing, timeline grid math. Plain classes with constructor-injected
dependencies. Nothing here knows what a Composable is.

**This is the layer that catches most of the value.** A rule implemented in a ViewModel is
tied to one screen and gets duplicated — or, worse, forgotten — when a second screen needs
it. The export-allowance bug in [samples/01](../samples/01-clean-architecture) is exactly
that: the rule lived on one screen, and a second screen shipped without it.

### `:core:viewModel` and `:core:design` — presentation

ViewModels expose `StateFlow<UiState>` and accept intents. `:core:design` holds the theme,
typography, and the reusable instrument controls — knobs, pads, faders, meters — so a new
instrument screen composes existing parts rather than restyling from scratch.

### `:core:networking`, `:dataSource`, `:core:repository` — data

23 Retrofit services, Room (7 entities, schema v4), DataStore for project state, and the
`NativeBridge`. Repositories return domain models; DTOs never escape this layer.

### Native

Everything under the JNI boundary. Only `:dataSource` may call it.

## Cross-cutting decisions

**One JNI surface.** All 126 `external fun` declarations in one object. JNI has no
compile-time linkage between Kotlin and C++, so a rename is a runtime crash on a user's
device. One file means one audit point.

**The audio engine owns the clock.** Anything that needs to know "where are we in the
song" observes the engine rather than running its own timer. Two clocks drift, and drift
between the UI playhead and the audible position is immediately obvious to a musician.

**State flows one way.** UI → intent → ViewModel → domain → data/native → state → UI. There
is no path by which a Composable mutates engine state directly.

## Where the design is under tension

Honest notes, since these are the things I would ask about in a review:

- **Two UI paradigms coexist.** Newer surfaces are Compose; older ones are Fragments and
  Views. Interop is managed deliberately, but it is real complexity, and it is the cost of
  migrating a shipping app rather than rewriting it.
- **`:core:managers` is large.** It is the most likely candidate for a split — probably by
  bounded context (transport, editing, commerce) rather than by type.
- **The JNI surface is wide.** 126 methods is a lot of contract to keep in sync by hand.
  A generated binding would be the principled fix; it has not yet cleared the bar against
  other work.
