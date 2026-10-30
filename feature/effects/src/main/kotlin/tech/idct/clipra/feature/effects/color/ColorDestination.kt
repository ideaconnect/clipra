package tech.idct.clipra.feature.effects.color

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.component.VideoSurface
import tech.idct.clipra.core.engine.preview.PreviewPlayer
import tech.idct.clipra.core.model.ColorGrade

/**
 * The Color board on the editor's open project (#28): its own preview at the editor's playhead,
 * split into before and after, the grade shown live while a slider moves, each change an edit
 * in the editor's history.
 */
@Composable
internal fun ColorDestination(onDone: () -> Unit, viewModel: ColorViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) { onDispose { player.release() } }
    var live by remember { mutableStateOf<ColorGrade?>(null) }
    var split by remember { mutableFloatStateOf(0.5f) }
    val pickLut = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
        it?.let(viewModel::importLut)
    }
    BackHandler(onBack = onDone)

    LaunchedEffect(state.clip?.id) {
        state.clip?.let { player.setColorSplit(it.id, split) }
    }
    LaunchedEffect(state.project) {
        live = null
        val project = state.project ?: return@LaunchedEffect
        player.setProject(project, viewModel.playheadUs)
    }
    if (state.loaded && state.clip == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return

    ColorScreen(
        state = state,
        grade = live ?: state.grade,
        split = split,
        actions = ColorActions(
            onBack = onDone,
            onLive = {
                live = it
                player.setLiveColor(clip.id, it)
            },
            onGrade = {
                live = it
                player.setLiveColor(clip.id, it)
                viewModel.setGrade(it)
            },
            onDone = { live?.let(viewModel::setGrade) },
            onSplit = {
                split = it
                player.setColorSplit(clip.id, it)
            },
            onAuto = viewModel::auto,
            onReset = viewModel::reset,
            onPaste = viewModel::paste,
            onImport = { pickLut.launch(arrayOf("*/*")) },
            onDismissError = viewModel::dismissImportError,
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
