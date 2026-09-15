package com.nextsoundz.showcase.sequencer

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * DEMONSTRATION SAMPLE — rewritten for this public repository.
 *
 * ---
 *
 * The Compose half of the pattern. Two things to note:
 *
 *  1. **A stateful entry point and a stateless body.** [StepSequencerScreen] wires the
 *     ViewModel; [StepSequencerContent] takes state and a callback. Only the second one is
 *     needed for a `@Preview` or a Compose UI test — no Hilt, no ViewModel, no engine.
 *
 *  2. **`collectAsStateWithLifecycle`, not `collectAsState`.** The plain version keeps
 *     collecting while the screen is in the background. Here that means holding a
 *     subscription to the audio engine's playhead for a screen nobody is looking at:
 *     wasted wakeups and, in the real app, needless contention with the audio thread.
 */
@Composable
fun StepSequencerScreen(
    viewModel: StepSequencerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    StepSequencerContent(state = state, onEvent = viewModel::onEvent)
}

@Composable
fun StepSequencerContent(
    state: SequencerUiState,
    onEvent: (SequencerEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        TransportBar(
            bpm = state.bpm,
            isPlaying = state.isPlaying,
            onEvent = onEvent,
        )

        state.tracks.forEachIndexed { trackIndex, track ->
            StepRow(
                track = track,
                currentStep = state.currentStep,
                // The lambda captures the *index*, not the track object. Capturing the
                // object means a gesture that starts before a state change writes back a
                // stale value — a real bug I shipped a fix for on the DAW timeline.
                onToggle = { step -> onEvent(SequencerEvent.ToggleStep(trackIndex, step)) },
            )
        }
    }
}

@Composable
private fun TransportBar(
    bpm: Int,
    isPlaying: Boolean,
    onEvent: (SequencerEvent) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(onClick = { onEvent(SequencerEvent.PlayPause) }) {
            Text(if (isPlaying) "Stop" else "Play")
        }

        Text("$bpm BPM", style = MaterialTheme.typography.labelLarge)

        Slider(
            value = bpm.toFloat(),
            onValueChange = { onEvent(SequencerEvent.SetTempo(it.toInt())) },
            valueRange = StepSequencerViewModel.MIN_BPM.toFloat()..
                StepSequencerViewModel.MAX_BPM.toFloat(),
            modifier = Modifier.width(180.dp),
        )
    }
}

@Composable
private fun StepRow(
    track: SequencerUiState.TrackRow,
    currentStep: Int,
    onToggle: (Int) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = track.name,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.width(56.dp),
        )

        track.steps.forEachIndexed { index, enabled ->
            StepPad(
                enabled = enabled,
                isCurrent = index == currentStep,
                // Downbeat accent every 4 steps — a visual bar grid.
                isAccent = index % 4 == 0,
                onClick = { onToggle(index) },
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun StepPad(
    enabled: Boolean,
    isCurrent: Boolean,
    isAccent: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fill = when {
        enabled -> MaterialTheme.colorScheme.primary
        isAccent -> MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surface
    }

    Column(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(fill)
            .border(
                width = if (isCurrent) 2.dp else 0.dp,
                color = if (isCurrent) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .clickable(onClick = onClick),
    ) {}
}
