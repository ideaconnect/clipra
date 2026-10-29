package tech.idct.clipra.feature.effects.transitions

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.component.VideoSurface
import tech.idct.clipra.core.engine.preview.PreviewPlayer
import tech.idct.clipra.core.model.Transitions
import tech.idct.clipra.feature.effects.common.TILE_HEIGHT
import kotlin.math.roundToInt

/**
 * The Transitions screen on the editor's open project: the preview rests on the cut, halfway
 * through the transition, and Play runs it with a little either side.
 */
@Composable
internal fun TransitionsDestination(
    onDone: () -> Unit,
    viewModel: TransitionsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) { onDispose { player.release() } }
    var liveDuration by remember { mutableStateOf<Long?>(null) }
    var playing by remember { mutableStateOf(false) }
    BackHandler(onBack = onDone)

    val cutUs = state.cut?.timeUs
    LaunchedEffect(state.project) {
        liveDuration = null
        val project = state.project ?: return@LaunchedEffect
        player.setProject(project, cutUs ?: 0)
    }
    LaunchedEffect(playing) {
        if (!playing || cutUs == null) return@LaunchedEffect
        val half = (state.transition?.durationUs ?: Transitions.DEFAULT_DURATION_US) / 2
        val endUs = cutUs + half + PLAY_MARGIN_US
        player.seekTo((cutUs - half - PLAY_MARGIN_US).coerceAtLeast(0))
        player.play()
        while (player.positionUs < endUs && player.isPlaying) withFrameNanos { }
        player.pause()
        player.seekTo(cutUs)
        playing = false
    }
    if (state.loaded && state.cut == null) {
        // The cut is gone (a clip was moved, undone or deleted).
        LaunchedEffect(Unit) { onDone() }
        return
    }

    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    LaunchedEffect(state.project != null) {
        val height = with(density) { TILE_HEIGHT.roundToPx() }
        val width = ((window.width - with(density) { 64.dp.roundToPx() }) / 5f).roundToInt()
        viewModel.loadThumbnails(width.coerceAtLeast(height), height)
    }

    TransitionsScreen(
        state = state,
        durationUs = liveDuration ?: state.transition?.durationUs
            ?: Transitions.DEFAULT_DURATION_US,
        actions = TransitionsActions(
            onBack = onDone,
            onCategory = viewModel::category,
            onPick = viewModel::pick,
            onDuration = { liveDuration = it },
            onDurationDone = { liveDuration?.let(viewModel::setDuration) },
            onEasing = viewModel::setEasing,
            onPlay = { playing = true },
            onApplyToAll = viewModel::applyToAll,
        ),
        preview = {
            VideoSurface(
                onAttach = { surface, width, height -> player.attach(surface, width, height) },
                onDetach = player::detach,
                modifier = Modifier.fillMaxSize(),
            )
        },
    )
}

/** Play shows this much before and after the transition. */
private const val PLAY_MARGIN_US = 500_000L
