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

## A worked example: auditing a large codebase

The strongest use I have found is systematic sweeps over a surface too large to review by
hand. A 37-module repository with 1,593 commits has more corners than I can hold in my head,
and the things that hide in those corners — an inconsistent pattern, a dependency that should
not be there, a file that is tracked when everyone assumes it is ignored — are exactly the
kind of thing a tireless reader finds and a busy one walks past.

What makes it work is that the output is *verifiable*. A sweep produces a list of specific
files and lines. Each one is either a real problem or it is not, and checking takes seconds.
That is a very different proposition from asking a model to reason about behaviour, where a
confident wrong answer looks identical to a right one.

So the division holds: the tool is excellent at **finding candidates** across breadth I could
not cover manually. Deciding which candidates are real, how serious they are, and what to do
about them stays with me — and for anything security-related, the fix does too.

## The summary I would give a team

Treat it as a fast, tireless colleague with excellent recall and **no accountability**.
Outstanding for breadth, navigation, first drafts, and finding what you walked past. Never
the final word on correctness. Never trusted on the parts of the system where being
confidently wrong is expensive — and knowing which parts those are is the engineer's job,
not the tool's.
