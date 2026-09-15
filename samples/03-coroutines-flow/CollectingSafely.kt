package com.nextsoundz.showcase.events

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * Three coroutine habits that keep a long-lived audio app stable.
 */
object CollectingSafely {

    /**
     * **1. Lifecycle-correct collection.**
     *
     * `lifecycleScope.launch { flow.collect { } }` is the common mistake: it keeps collecting
     * while the screen is stopped. For a flow fed by the audio engine that means continuing
     * to marshal engine updates for a screen nobody can see.
     *
     * `repeatOnLifecycle` cancels collection at STOP and restarts it at START.
     */
    fun <T> LifecycleOwner.collectWhileStarted(flow: Flow<T>, block: suspend (T) -> Unit) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                flow.collect { block(it) }
            }
        }
    }

    /**
     * **2. A supervised application scope for work that must outlive a screen.**
     *
     * Rendering an export must not be cancelled because the user rotated the device or
     * navigated away. This scope is application-lifetime, uses [SupervisorJob] so one failed
     * child does not cancel its siblings, and carries a handler so an uncaught exception is
     * reported rather than silently killing the scope.
     *
     * Used sparingly and deliberately — an app-scoped coroutine that outlives everything is
     * also a great way to leak, so each use has to justify itself.
     */
    fun applicationScope(onError: (Throwable) -> Unit): CoroutineScope =
        CoroutineScope(
            SupervisorJob() +
                Dispatchers.Default +
                CoroutineExceptionHandler { _, t -> onError(t) },
        )

    /**
     * **3. Explicit dispatcher boundaries at the point of blocking work.**
     *
     * The rule I follow: a suspend function is main-safe, and it is the *callee's* job to
     * make that true. A caller should never need to know that decoding touches the disk.
     *
     * The dispatcher is injected rather than hardcoded so tests substitute a test dispatcher
     * and run deterministically.
     */
    suspend fun decodeWaveformPreview(
        path: String,
        io: kotlin.coroutines.CoroutineContext = Dispatchers.IO,
        decode: (String) -> FloatArray,
    ): FloatArray = withContext(io) { decode(path) }

    /**
     * A flow that must never take down its collector. Engine event streams use this: a
     * malformed event should degrade to a logged error, not crash a recording session.
     */
    fun <T> Flow<T>.reportingFailures(onError: (Throwable) -> Unit): Flow<T> =
        catch { t -> onError(t) }
}
