package tech.idct.clipra.feature.effects.reframe

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.component.ReadableWidth
import tech.idct.clipra.feature.effects.R

/**
 * Auto-reframe on the editor's open project (#53). Once the copy is made, [onCreated] opens it
 * in the editor; Back stops the tracking and leaves the project as it was.
 */
@Composable
internal fun ReframeDestination(
    playheadUs: Long,
    onDone: () -> Unit,
    onCreated: (projectId: String) -> Unit,
    viewModel: ReframeViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    BackHandler(onBack = onDone)
    if (state.loaded && state.project == null) {
        LaunchedEffect(Unit) { onDone() }
        return
    }
    state.created?.let { id -> LaunchedEffect(id) { onCreated(id) } }
    // "Lisbon weekend · Shorts": the copy is named after its format.
    val name = state.project?.let {
        stringResource(R.string.reframe_name, it.name, stringResource(copyNameRes(state.ratio)))
    }
    ReadableWidth {
        ReframeScreen(
            state = state,
            playheadUs = playheadUs,
            actions = ReframeActions(
                onBack = onDone,
                onRatio = viewModel::setRatio,
                onFollow = viewModel::setFollow,
                onMotion = viewModel::setMotion,
                onKeepInside = viewModel::setKeepInside,
                onCreate = { name?.let(viewModel::create) },
                onCancel = viewModel::cancel,
            ),
        )
    }
}
