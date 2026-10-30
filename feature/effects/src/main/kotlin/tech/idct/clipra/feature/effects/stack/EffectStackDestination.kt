package tech.idct.clipra.feature.effects.stack

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
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters

/**
 * The effect stack (#29) on the editor's open project: its own preview at the editor's
 * playhead, a slider shown live while it moves, each change an edit in the editor's history.
 */
@Composable
internal fun EffectStackDestination(
    onDone: () -> Unit,
    viewModel: EffectStackViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) { onDispose { player.release() } }
    var live by remember { mutableStateOf<List<Effect>?>(null) }
    BackHandler(onBack = onDone)

    LaunchedEffect(state.project) {
        live = null
        state.project?.let { player.setProject(it, viewModel.playheadUs) }
    }
    if (state.loaded && state.clip == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return

    /** Shows the stack with [change] made to the effects [where] picks, while a slider moves. */
    fun showLive(change: (Effect) -> Effect, where: (Int, Effect) -> Boolean) {
        val current = live ?: state.effects
        val effects = current.mapIndexed { i, e -> if (where(i, e)) change(e) else e }
        live = effects
        player.setLiveEffects(clip.id, effects)
    }

    EffectStackScreen(
        state = state,
        effects = live ?: state.effects,
        actions = EffectStackActions(
            onBack = onDone,
            onAdd = viewModel::add,
            onMove = viewModel::move,
            onEnabled = viewModel::setEnabled,
            onStrengthLive = { index, strength ->
                showLive({ it.copy(strength = strength) }) { i, _ -> i == index }
            },
            onStrengthDone = { index ->
                live?.getOrNull(index)?.let { viewModel.setStrength(index, it.strength) }
            },
            onBlockSizeLive = { size ->
                val sized = Filters.MOSAIC_SIZE to size
                showLive({ it.copy(params = it.params + sized) }) { _, e -> e.type == "mosaic" }
            },
            onBlockSizeDone = {
                live?.firstOrNull { it.type == "mosaic" }?.params?.get(Filters.MOSAIC_SIZE)
                    ?.let(viewModel::setBlockSize)
            },
            onRemove = viewModel::remove,
            onCopy = viewModel::copy,
            onPaste = viewModel::paste,
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
