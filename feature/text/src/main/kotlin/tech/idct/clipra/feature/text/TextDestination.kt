package tech.idct.clipra.feature.text

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
import kotlinx.coroutines.delay
import tech.idct.clipra.core.designsystem.component.VideoSurface
import tech.idct.clipra.core.engine.preview.PreviewPlayer

/**
 * The Text screen on the editor's open project: its own preview at the editor's playhead,
 * typing committed a moment after it stops (one undo step per pause), and the rest at once.
 */
@Composable
internal fun TextDestination(onDone: () -> Unit, viewModel: TextViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val player = remember { PreviewPlayer(context, viewModel.mediaCache) }
    DisposableEffect(player) { onDispose { player.release() } }

    LaunchedEffect(state.project) {
        state.project?.let { player.setProject(it, viewModel.playheadUs) }
    }
    if (state.loaded && state.clip == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    val clip = state.clip ?: return
    val spec = clip.text ?: return
    var draft by remember(clip.id) { mutableStateOf(spec.text) }
    LaunchedEffect(draft) {
        delay(TYPING_PAUSE_MS)
        val current = viewModel.state.value.clip?.text ?: return@LaunchedEffect
        if (draft != current.text) viewModel.setSpec(current.copy(text = draft))
    }
    // Keeps what was typed last.
    val commitDraft = {
        val current = viewModel.state.value.clip?.text
        if (current != null && draft != current.text) viewModel.setSpec(current.copy(text = draft))
    }
    val finish = {
        commitDraft()
        onDone()
    }
    BackHandler(onBack = finish)

    TextScreen(
        state = state,
        draft = draft,
        templates = templates,
        actions = TextActions(
            onBack = finish,
            onDraft = { draft = it },
            onSpec = { viewModel.setSpec(it.copy(text = draft)) },
            onAnimation = viewModel::setAnimation,
            onTemplate = {
                commitDraft()
                viewModel.applyTemplate(it)
            },
            onSaveTemplate = {
                commitDraft()
                viewModel.saveTemplate()
            },
            onDeleteTemplate = viewModel::deleteTemplate,
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

private const val TYPING_PAUSE_MS = 600L
