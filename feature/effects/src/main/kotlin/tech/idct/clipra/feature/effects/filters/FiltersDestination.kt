package tech.idct.clipra.feature.effects.filters

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.component.VideoSurface
import tech.idct.clipra.core.engine.preview.PreviewPlayer
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.filter
import kotlin.math.roundToInt

/**
 * The Filters screen on the editor's open project: its own preview at the editor's playhead,
 * the strength slider and hold-to-compare shown live, and every pick an edit in the editor's
 * undo history.
 */
@Composable
internal fun FiltersDestination(onDone: () -> Unit, viewModel: FiltersViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) { onDispose { player.release() } }
    var liveStrength by remember { mutableStateOf<Float?>(null) }
    BackHandler(onBack = onDone)

    LaunchedEffect(state.project) {
        liveStrength = null
        state.project?.let { player.setProject(it, viewModel.playheadUs) }
    }
    if (state.loaded && state.clip == null) {
        // The clip is gone (undone or deleted elsewhere).
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return

    // Thumbnails at the tiles' size: five across the screen, 48 dp tall.
    val density = LocalDensity.current
    val window = LocalWindowInfo.current.containerSize
    LaunchedEffect(state.project != null) {
        val height = with(density) { TILE_HEIGHT.roundToPx() }
        val width = ((window.width - with(density) { 64.dp.roundToPx() }) / 5f).roundToInt()
        viewModel.loadThumbnails(width.coerceAtLeast(height), height)
    }

    val filter = clip.filter
    FiltersScreen(
        state = state,
        strength = liveStrength ?: filter?.strength ?: Filters.DEFAULT_STRENGTH,
        actions = FiltersActions(
            onBack = onDone,
            onCategory = viewModel::category,
            onPick = viewModel::pick,
            onStrength = { strength ->
                if (filter != null) {
                    liveStrength = strength
                    val effects = clip.effects.map {
                        if (it == filter) it.copy(strength = strength) else it
                    }
                    player.setLiveEffects(clip.id, effects)
                }
            },
            onStrengthDone = { liveStrength?.let(viewModel::setStrength) },
            onCompare = { holding ->
                val original = clip.effects.filterNot { it == filter }
                player.setLiveEffects(clip.id, if (holding) original else null)
            },
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
