package tech.idct.clipra.feature.effects.cutout

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
import tech.idct.clipra.core.model.Cutout

/**
 * The Cutout board on the editor's open project (#50): its own preview at the editor's playhead,
 * the cutout shown live while a slider moves, each change an edit in the editor's history. The
 * preview picks up the masks when they are ready.
 */
@Composable
internal fun CutoutDestination(onDone: () -> Unit, viewModel: CutoutViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) { onDispose { player.release() } }
    var live by remember { mutableStateOf<Cutout?>(null) }
    val ready by viewModel.mediaCache.voiceVersion.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)

    LaunchedEffect(state.project, ready) {
        live = null
        val project = state.project ?: return@LaunchedEffect
        player.setProject(project, viewModel.playheadUs)
    }
    if (state.loaded && state.clip == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return

    CutoutScreen(
        state = state,
        cutout = live ?: state.cutout,
        actions = CutoutActions(
            onBack = onDone,
            onLive = {
                live = it
                player.setLiveCutout(clip.id, it)
            },
            onCutout = {
                live = it
                player.setLiveCutout(clip.id, it)
                viewModel.set(it)
            },
            onRemove = {
                viewModel.remove()
                onDone()
            },
            onChroma = {
                live = null
                player.setLiveCutout(clip.id, null)
                viewModel.setChroma(it)
            },
            onPick = viewModel::pickAt,
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
