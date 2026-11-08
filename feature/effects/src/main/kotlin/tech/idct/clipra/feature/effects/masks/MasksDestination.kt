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
    var liveStrength by remember { mutableStateOf<Float?>(null) }
    BackHandler(onBack = onDone)

    LaunchedEffect(state.project) {
        liveMask = null
        liveBlock = null
        liveStrength = null
        state.project?.let { player.setProject(it, viewModel.playheadUs) }
    }
    if (state.loaded && state.clip == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return
    val mask = liveMask ?: state.mask
    val mosaic = clip.effects.firstOrNull { it.type == "mosaic" }
    // The effect the mask limits (#239), whose strength the board shows.
    val limited = clip.effects.firstOrNull { it.type == (mask?.effect ?: state.effectType) }

    // The clip's effects with the block size and strength the sliders show while they move.
    fun liveEffects(size: Float?, strength: Float?) = clip.effects.map {
        val sized = if (size != null && it == mosaic) {
            it.copy(params = it.params + (Filters.MOSAIC_SIZE to size))
        } else {
            it
        }
        if (strength != null && it == limited) sized.copy(strength = strength) else sized
    }

    MasksScreen(
        state = state,
        mask = mask,
        strength = liveStrength ?: limited?.strength ?: Filters.MASK_STRENGTH,
        blockSize = liveBlock ?: mosaic?.params?.get(Filters.MOSAIC_SIZE)
            ?: Filters.DEFAULT_MOSAIC_SIZE,
        actions = MasksActions(
            onBack = onDone,
            onShape = viewModel::shape,
            onMaskLive = { moved ->
                liveMask = moved
                player.setLiveMask(clip.id, moved)
            },
            onMask = viewModel::setMask,
            onMaskDone = { liveMask?.let(viewModel::setMask) },
            onBlockSizeLive = { size ->
                if (mosaic != null) {
                    liveBlock = size
                    player.setLiveEffects(clip.id, liveEffects(size, liveStrength))
                }
            },
            onBlockSizeDone = { liveBlock?.let(viewModel::setBlockSize) },
            onKeyframe = viewModel::toggleKeyframe,
            onFollowFace = viewModel::followFace,
            onStrengthLive = { strength ->
                if (limited != null) {
                    liveStrength = strength
                    player.setLiveEffects(clip.id, liveEffects(liveBlock, strength))
                }
            },
            onStrengthDone = {
                val strength = liveStrength
                if (limited != null && strength != null) {
                    viewModel.setStrength(limited.type, strength)
                }
            },
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
