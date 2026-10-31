package tech.idct.clipra.feature.audio

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.component.ReadableWidth
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.model.Clip

/** The sound of [clipId] in [projectId]; no project opens the placeholder board (screen index). */
@Serializable
data class AudioRoute(
    val projectId: String? = null,
    val clipId: String? = null,
    val playheadUs: Long = 0,
)

/**
 * The boards this module builds, from design/README.md ("Audio, Clipra Pro and AI models").
 */
object AudioBoards {
    val audio = Board(id = "Audio", title = R.string.audio_title_audio, route = AudioRoute())

    val all = listOf(audio)
}

/** Where the audio screens lead; the app connects it. */
class AudioActionsNavigation(val onDone: () -> Unit)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.audioScreens(
    screen: @Composable (Board) -> Unit,
    actions: AudioActionsNavigation,
) {
    composable<AudioRoute> { entry ->
        if (entry.toRoute<AudioRoute>().projectId == null) {
            screen(AudioBoards.audio)
        } else {
            AudioDestination(onDone = actions.onDone)
        }
    }
}

/** The Audio screen on the editor's open project; each change is one undo step. */
@Composable
private fun AudioDestination(onDone: () -> Unit, viewModel: AudioViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    if (state.loaded && state.clip == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return
    val editing = audioEditing(viewModel, state, clip, onDone)
    ReadableWidth {
        AudioScreen(
            state = state,
            volume = editing.volume,
            strength = editing.strength,
            noiseStrength = editing.noise,
            duckingDepth = editing.duck,
            actions = editing.actions,
        )
    }
}

/** The sliders' values while they move, and the actions that commit each change. */
internal class AudioEditing(
    val volume: Float,
    val strength: Float?,
    val noise: Float?,
    val duck: Float?,
    val actions: AudioActions,
)

/** Editing [clip]'s sound through [viewModel]; a slider is one undo step when let go. */
@Composable
internal fun audioEditing(
    viewModel: AudioViewModel,
    state: AudioUiState,
    clip: Clip,
    onBack: () -> Unit,
): AudioEditing {
    var liveVolume by remember(clip.id) { mutableStateOf<Float?>(null) }
    var liveStrength by remember(clip.id) { mutableStateOf<Float?>(null) }
    var liveNoise by remember(clip.id) { mutableStateOf<Float?>(null) }
    var liveDuck by remember(clip.id) { mutableStateOf<Float?>(null) }
    LaunchedEffect(state.project) {
        liveVolume = null
        liveStrength = null
        liveNoise = null
        liveDuck = null
    }
    return AudioEditing(
        volume = liveVolume ?: clip.audio.volume,
        strength = liveStrength,
        noise = liveNoise,
        duck = liveDuck,
        actions = AudioActions(
            onBack = onBack,
            onVolume = { liveVolume = it },
            onVolumeDone = {
                liveVolume?.let { viewModel.setAudio(clip.audio.copy(volume = it)) }
            },
            onAudio = viewModel::setAudio,
            onLoudnessTarget = viewModel::setLoudnessTarget,
            onVoice = viewModel::setVoiceOn,
            onEngine = viewModel::chooseEngine,
            onStrength = { liveStrength = it },
            onStrengthDone = { liveStrength?.let(viewModel::setVoiceStrength) },
            onApplyToAll = viewModel::applyVoiceToAll,
            onNoise = viewModel::setNoiseOn,
            onNoiseStrength = { liveNoise = it },
            onNoiseStrengthDone = { liveNoise?.let(viewModel::setNoiseStrength) },
            onDucking = viewModel::setDucking,
            onDuckingDepth = { liveDuck = it },
            onDuckingDepthDone = { liveDuck?.let(viewModel::setDuckingDepth) },
            onBeats = viewModel::setBeats,
        ),
    )
}
