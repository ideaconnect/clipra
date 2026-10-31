package tech.idct.clipra.feature.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType

/**
 * The Audio inspector in the tablet editor (#70, TabletAudio board), for the selected clip with
 * sound: its waveform and loudness before and after, volume, fades, normalization, noise
 * reduction, AI voice cleanup, ducking and beats. A slider is committed when it is let go.
 */
@Composable
fun AudioInspectorPanel(
    clipId: String,
    modifier: Modifier = Modifier,
    viewModel: AudioViewModel = hiltViewModel(key = "tablet-audio"),
) {
    LaunchedEffect(clipId) { viewModel.select(clipId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val clip = state.clip?.takeIf { it.id == clipId }
    if (clip == null || state.project == null) {
        Box(modifier)
        return
    }
    AudioInspector(state, clip, audioEditing(viewModel, state, clip, onBack = {}), modifier)
}

/** The sound settings of [clip] under its name and layer. */
@Composable
internal fun AudioInspector(
    state: AudioUiState,
    clip: Clip,
    editing: AudioEditing,
    modifier: Modifier = Modifier,
) {
    val project = state.project ?: return
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val asset = clip.source?.let { project.asset(it.mediaId) }
    val layer = project.layers.indexOfFirst { layer -> layer.clips.any { it.id == clip.id } } + 1
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("audio-inspector"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(
                        when {
                            asset?.recorded == true -> colors.clip.voice
                            clip.type == ClipType.Audio -> colors.clip.music
                            else -> colors.textPrimary
                        },
                    ),
            )
            Text(
                text = asset?.displayName.orEmpty(),
                style = type.bodyStrong,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.audio_inspector_place, layer),
                style = type.meta,
                color = colors.textSecondary,
            )
        }
        AudioControls(
            state,
            clip,
            editing.volume,
            editing.actions,
            editing.strength,
            editing.noise,
            editing.duck,
        )
    }
}
