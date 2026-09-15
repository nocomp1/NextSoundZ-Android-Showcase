# AI-assisted engineering workflow

I use Claude Code daily on this codebase. This documents where it helps, where it does not,
and how I keep it from becoming a liability — because the second part is the part that
actually requires judgement.

## Where it earns its place

**Navigating 37 modules.** Tracing a symptom from a Compose screen through a ViewModel, a
manager, `NativeBridge`, and into C++ is wide, shallow search across a codebase too large to
hold in my head. That is the single highest-value use.

**Java-to-Kotlin conversion review.** The IDE converter leaves `!!` and platform types
wherever it could not prove non-nullity. Systematically finding and reasoning about each one
is tedious and mechanical — exactly the shape of work to delegate, with review.

**Writing regression tests from a bug report.** Given a reproduction and the fix, drafting a
test that pins the behaviour, including the "why this exists" KDoc that the
[testing strategy](testing-strategy.md) insists on.

**Rubber-ducking architecture before writing code.** Especially at the JNI boundary, where
the cost of discovering a design problem after implementation is high.

**A persistent project memory.** The highest-leverage thing I do with it. I maintain
structured notes of hard-won findings — the root cause of a specific crash, why a plausible
theory was investigated and *reverted*, which of two native code paths registers a preset
catalog and which silently does not. Six months later a related bug starts from accumulated
evidence instead of from scratch. Recording the theories that turned out to be **wrong** has
saved as much time as recording the fixes.

## Where I keep it out of the loop

**The real-time audio path.** Code inside the audio callback has constraints that are not
visible in the code itself: no allocation, no locks, no JNI, no logging. A suggestion can
look completely idiomatic and violate all four. The failure is not a compile error or a
failing test — it is an intermittent click on some users' devices. I write and review that
by hand.

**Anything a device must confirm.** Audio latency, MIDI timing, `AudioPlaybackCapture`
behaviour, and manufacturer audio-stack quirks are verified on real hardware, including
deliberately old hardware. One sampling path records silence on Android 10 on two specific
devices. No amount of reasoning would have predicted that, and a green test suite would have
told me everything was fine.

**Security-relevant code.** Reviewed manually, always.

## Practices that make it work

**Ask for the diagnosis before the fix.** "Explain why this happens" produces a better
outcome than "fix this", and it surfaces a wrong theory before it becomes a wrong patch.

**Demand the evidence.** When it claims a cause, I want the file and line. A confident
explanation citing code that does not exist is the characteristic failure mode, and it is
convincing precisely when you are tired.

**Verify against the running app.** I traced a "late notes" bug to ~30ms of Android
input-dispatch lag by instrumenting and measuring. The plausible theory — that my own
per-note write path was slow — was wrong, and it was wrong in a way that would have survived
any amount of code review.

**Keep it away from the merge button.** It drafts; I review, run, and take responsibility.

## A worked example: this repository

This showcase was produced by an AI-assisted audit of the private repository. The audit
swept the working tree and git history for credential patterns and found:

- a **live payment provider secret key** committed in 2023 and still present at HEAD, in a
  file that `.gitignore` listed — which does nothing for an already-tracked file;
- **release keystore passwords** in a committed `gradle.properties`;
- a **TLS hostname verifier returning `true` unconditionally**, disabling certificate
  validation.

Three real findings, in a codebase I know well, that I had walked past for years. That is a
genuine demonstration of value: tireless, systematic breadth over a large surface.

And every one of them still needed a human to confirm it was real, judge the severity, and
do the part that actually mattered — rotating the keys. The tool found them. It could not
have fixed them, and it would have been dangerous to let it try.

## The summary I would give a team

Treat it as a fast, tireless colleague with excellent recall and **no accountability**.
Outstanding for breadth, navigation, first drafts, and finding what you walked past. Never
the final word on correctness. Never trusted on the parts of the system where being
confidently wrong is expensive — and knowing which parts those are is the engineer's job,
not the tool's.
