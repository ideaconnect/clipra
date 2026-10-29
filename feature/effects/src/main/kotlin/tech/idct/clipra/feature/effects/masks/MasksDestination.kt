package tech.idct.clipra.feature.effects.masks

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
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.component.VideoSurface
import tech.idct.clipra.core.engine.preview.PreviewPlayer
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask

/**
 * The Masks screen on the editor's open project: its own preview at the editor's playhead, the
 * mask and the sliders shown live while they move, each change an edit in the editor's history.
 */
@Composable
internal fun MasksDestination(onDone: () -> Unit, viewModel: MasksViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) { onDispose { player.release() } }
    var liveMask by remember { mutableStateOf<Mask?>(null) }
    var liveBlock by remember { mutableStateOf<Float?>(null) }
    BackHandler(onBack = onDone)

    LaunchedEffect(state.project) {
        liveMask = null
        liveBlock = null
        state.project?.let { player.setProject(it, viewModel.playheadUs) }
    }
    if (state.loaded && state.clip == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return
    val mosaic = clip.effects.firstOrNull { it.type == "mosaic" }

    MasksScreen(
        state = state,
        mask = liveMask ?: clip.mask,
        blockSize = liveBlock ?: mosaic?.params?.get(Filters.MOSAIC_SIZE)
            ?: Filters.DEFAULT_MOSAIC_SIZE,
        actions = MasksActions(
            onBack = onDone,
            onShape = viewModel::shape,
            onMaskLive = { mask ->
                liveMask = mask
                player.setLiveMask(clip.id, mask)
            },
            onMask = viewModel::setMask,
            onMaskDone = { liveMask?.let(viewModel::setMask) },
            onBlockSizeLive = { size ->
                if (mosaic != null) {
                    liveBlock = size
                    val sized = mosaic.copy(params = mosaic.params + (Filters.MOSAIC_SIZE to size))
                    val effects = clip.effects.map { if (it == mosaic) sized else it }
                    player.setLiveEffects(clip.id, effects)
                }
            },
            onBlockSizeDone = { liveBlock?.let(viewModel::setBlockSize) },
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
