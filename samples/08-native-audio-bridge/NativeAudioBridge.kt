package com.nextsoundz.showcase.native

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository. The production bridge
 * declares 126 native methods. No DSP, mixing, or synthesis code is published here.
 *
 * ---
 *
 * The single Kotlin↔C++ seam.
 *
 * **Why one object.** Every `external fun` in the app lives in one file. JNI has no
 * compile-time link checking between Kotlin and C++: rename a method on one side and you
 * get an `UnsatisfiedLinkError` at runtime, on a user's device, usually in the one code
 * path QA did not hit. Funnelling the whole surface through one object means the contract
 * has exactly one place to audit, and a native signature change has exactly one file to
 * update.
 *
 * ## The rules at this boundary
 *
 * **1. Calls go down, not up — never from the audio thread.**
 * The JUCE audio callback runs on a real-time thread with a hard deadline (a few
 * milliseconds per buffer). Calling into the JVM from it is forbidden in practice: JNI
 * attach can block, and the callback may be interrupted by GC. So native never calls up
 * during the callback. It writes to a lock-free queue, and the JVM side drains it on a
 * scheduled clock and emits into a `Flow`. Everything the UI knows about playback arrives
 * that way.
 *
 * **2. Calls are coarse-grained.**
 * A JNI transition is expensive relative to the work in most individual setters. Rather
 * than crossing per note, the app serializes an entire arrangement and pushes it once.
 * Chatty boundaries are the classic way to starve an audio thread.
 *
 * **3. Pushes are idempotent and memoized.**
 * See [pushProjectState]. A real bug: any volume change re-pushed the whole project, and
 * native rebuilt every synth preset unconditionally — re-decoding sample data and briefly
 * dropping audio. The fix was a Kotlin-side memo so an unchanged preset is not re-applied.
 * The bug was native; the right place to fix it was here.
 *
 * **4. Every call is guarded against a torn-down engine.**
 * Activity teardown races audio shutdown. A call arriving after the native engine is
 * destroyed dereferences a dangling pointer and crashes natively, with a stack trace that
 * names none of your code. [engineReady] gates the surface.
 */
object NativeAudioBridge {

    init {
        System.loadLibrary("showcase_audio")
    }

    /**
     * False before `initEngine` and after `releaseEngine`. Every entry point checks it.
     *
     * `@Volatile` because teardown is initiated from the main thread while calls may arrive
     * from others; without it a stale cached `true` lets a call through into freed memory.
     */
    @Volatile
    private var engineReady: Boolean = false

    /** Guards the transition itself so two threads cannot init or release concurrently. */
    private val lifecycleLock = Any()

    // ---------------------------------------------------------------- lifecycle

    fun initEngine(sampleRate: Int, framesPerBurst: Int): Boolean =
        synchronized(lifecycleLock) {
            if (engineReady) return true
            // Device-optimal values from AudioManager. Guessing here causes the audio
            // layer to resample or re-buffer, which silently adds latency.
            engineReady = nativeInitEngine(sampleRate, framesPerBurst)
            engineReady
        }

    fun releaseEngine() = synchronized(lifecycleLock) {
        if (!engineReady) return
        // Order matters: stop the callback first, then free. Freeing while the audio
        // thread is mid-buffer is a use-after-free that surfaces as an unrelated crash.
        engineReady = false
        nativeReleaseEngine()
    }

    // ---------------------------------------------------------------- transport

    fun play() = ifReady { nativePlay() }
    fun stop(rewind: Boolean) = ifReady { nativeStop(rewind) }
    fun seek(positionMs: Long) = ifReady { nativeSeek(positionMs) }
    fun setTempo(bpm: Double) = ifReady { nativeSetTempo(bpm) }

    /**
     * Pushes the full arrangement. [stateJson] is a serialized project; native parses it
     * and rebuilds its graph.
     *
     * [changedTrackIndices] lets native skip rebuilding tracks that did not change — the
     * memoization described in rule 3. Passing null means "rebuild everything", which is
     * correct but expensive and audible.
     */
    fun pushProjectState(stateJson: String, changedTrackIndices: IntArray?) =
        ifReady { nativePushProjectState(stateJson, changedTrackIndices) }

    // ---------------------------------------------------------------- native → JVM

    /**
     * Drains playback position updates the audio thread queued.
     *
     * Pull, not push: the audio thread writes to a lock-free ring buffer and never calls
     * into the JVM. A scheduled task on this side drains it at UI cadence. The engine's
     * clock stays authoritative while the JVM stays entirely out of the real-time path.
     *
     * Returns the number of frames written into [out].
     */
    fun drainPositionUpdates(out: LongArray): Int =
        if (engineReady) nativeDrainPositionUpdates(out) else 0

    // ---------------------------------------------------------------- helpers

    private inline fun ifReady(block: () -> Unit) {
        if (engineReady) block()
    }

    // ------------------------------------------------- JNI declarations
    // Names and signatures must match the C++ side exactly. See NativeAudioBridge.cpp.

    private external fun nativeInitEngine(sampleRate: Int, framesPerBurst: Int): Boolean
    private external fun nativeReleaseEngine()
    private external fun nativePlay()
    private external fun nativeStop(rewind: Boolean)
    private external fun nativeSeek(positionMs: Long)
    private external fun nativeSetTempo(bpm: Double)
    private external fun nativePushProjectState(stateJson: String, changedTracks: IntArray?)
    private external fun nativeDrainPositionUpdates(out: LongArray): Int
}
