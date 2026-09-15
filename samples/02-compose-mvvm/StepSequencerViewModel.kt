package com.nextsoundz.showcase.sequencer

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import javax.inject.Inject

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository. The production sequencer
 * supports more tracks, per-step velocity/probability, swing, and pattern chaining.
 *
 * ---
 *
 * The MVVM + unidirectional-data-flow shape used across the app.
 *
 * Rules this illustrates:
 *
 *  - **One immutable state object per screen.** The UI renders [SequencerUiState] and nothing
 *    else. There is no second source of truth for the UI to disagree with.
 *  - **Intents in, state out.** The Composable calls `onEvent(...)`; it never mutates state
 *    and never touches a repository or the audio engine.
 *  - **The playhead is observed, not polled.** Position comes from the engine as a `Flow`.
 *    A `while (playing) { delay(16) }` loop would drift against the audio clock — the audio
 *    engine's clock is authoritative, because it is the thing actually producing sound.
 *  - **The engine is an interface.** [TransportController] is a domain abstraction, so this
 *    ViewModel is unit-testable with a fake and no native library loaded.
 */
@HiltViewModel
class StepSequencerViewModel @Inject constructor(
    private val transport: TransportController,
    private val patterns: PatternRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SequencerUiState())
    val uiState: StateFlow<SequencerUiState> = _uiState.asStateFlow()

    init {
        // The engine owns the clock. We mirror it into UI state rather than running our own
        // timer, so the highlighted step is always the step that is actually sounding.
        transport.playheadStep
            .onEach { step -> _uiState.update { it.copy(currentStep = step) } }
            .launchIn(viewModelScope)

        transport.isPlaying
            .onEach { playing -> _uiState.update { it.copy(isPlaying = playing) } }
            .launchIn(viewModelScope)
    }

    fun onEvent(event: SequencerEvent) {
        when (event) {
            is SequencerEvent.ToggleStep -> toggleStep(event.track, event.step)
            is SequencerEvent.SetTempo -> setTempo(event.bpm)
            SequencerEvent.PlayPause -> if (_uiState.value.isPlaying) transport.stop() else transport.play()
            SequencerEvent.ClearPattern -> clearPattern()
        }
    }

    private fun toggleStep(track: Int, step: Int) {
        // Update local state first so the pad responds on the next frame, then tell the
        // engine. The engine call is non-blocking and cheap; persistence is deferred.
        _uiState.update { state ->
            state.copy(tracks = state.tracks.mapIndexed { i, row ->
                if (i == track) row.copy(steps = row.steps.toggleAt(step)) else row
            })
        }
        transport.setStep(track, step, enabled = _uiState.value.tracks[track].steps[step])
        persist()
    }

    private fun setTempo(bpm: Int) {
        val clamped = bpm.coerceIn(MIN_BPM, MAX_BPM)
        _uiState.update { it.copy(bpm = clamped) }
        transport.setTempo(clamped)
        persist()
    }

    private fun clearPattern() {
        _uiState.update { state ->
            state.copy(tracks = state.tracks.map { it.copy(steps = List(STEP_COUNT) { false }) })
        }
        transport.clearAllSteps()
        persist()
    }

    /** Writes are debounced in the repository; the ViewModel just says "this changed". */
    private fun persist() {
        patterns.save(_uiState.value.toPattern())
    }

    private fun List<Boolean>.toggleAt(index: Int): List<Boolean> =
        mapIndexed { i, on -> if (i == index) !on else on }

    companion object {
        const val STEP_COUNT = 16
        const val MIN_BPM = 40
        const val MAX_BPM = 240
    }
}

/** Immutable snapshot of everything the sequencer screen renders. */
data class SequencerUiState(
    val bpm: Int = 120,
    val isPlaying: Boolean = false,
    val currentStep: Int = -1,
    val tracks: List<TrackRow> = defaultTracks(),
) {
    data class TrackRow(
        val id: Int,
        val name: String,
        val steps: List<Boolean>,
    )

    companion object {
        private fun defaultTracks() = listOf("Kick", "Snare", "Hat", "Clap")
            .mapIndexed { i, name ->
                TrackRow(i, name, List(StepSequencerViewModel.STEP_COUNT) { false })
            }
    }
}

/** Every user intent the screen can express. Exhaustive `when` keeps handling honest. */
sealed interface SequencerEvent {
    data class ToggleStep(val track: Int, val step: Int) : SequencerEvent
    data class SetTempo(val bpm: Int) : SequencerEvent
    data object PlayPause : SequencerEvent
    data object ClearPattern : SequencerEvent
}

/** Domain-owned abstraction over the native transport. See samples/08-native-audio-bridge. */
interface TransportController {
    val playheadStep: kotlinx.coroutines.flow.Flow<Int>
    val isPlaying: StateFlow<Boolean>
    fun play()
    fun stop()
    fun setTempo(bpm: Int)
    fun setStep(track: Int, step: Int, enabled: Boolean)
    fun clearAllSteps()
}

interface PatternRepository {
    fun save(pattern: Any)
}

private fun SequencerUiState.toPattern(): Any = this
