package tech.idct.clipra.feature.captions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChoiceCard
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.SecondaryButton

/**
 * Edit by text in the tablet editor (#70, TabletTranscript board), wide beside the preview: Cut
 * filler words and Cut pauses with the threshold, and every transcribed source's words, fillers
 * and pauses to tap. The cuts are marked across every layer of the timeline ([onMarks]) and made
 * by Cut, rippling, as one undo step; then [onDone].
 */
@Composable
fun EditByTextPanel(
    onMarks: (List<LongRange>) -> Unit,
    onDone: () -> Unit,
    onAutoCaptions: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EditByTextViewModel = hiltViewModel(key = "tablet-transcript"),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LaunchedEffect(state.ranges) { onMarks(state.ranges) }
    EditByText(
        state = state,
        actions = EditByTextActions(
            onBack = onDone,
            onDone = {
                viewModel.apply()
                viewModel.restoreAll()
                onDone()
            },
            onFillers = viewModel::setFillers,
            onPauses = viewModel::setPauses,
            onThreshold = viewModel::setThreshold,
            onToken = viewModel::toggle,
            onRestoreAll = viewModel::restoreAll,
            onPlay = {},
            onAutoCaptions = onAutoCaptions,
        ),
        modifier = modifier,
    )
}

/** Edit by text's choices, the sources' words and what the cuts come to. */
@Composable
internal fun EditByText(
    state: EditByTextUiState,
    actions: EditByTextActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .testTag("edit-by-text"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.captions_title_transcript),
                style = type.sectionTitle,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(R.string.captions_ripple),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        if (state.loaded && state.sources.isEmpty()) {
            NoTranscript(actions.onAutoCaptions)
            return@Column
        }
        val choices = state.choices
        val summary = state.summary
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChoiceCard(
                title = stringResource(R.string.captions_cut_fillers),
                subtitle = fillerNote(state),
                selected = choices.fillers,
                onClick = { actions.onFillers(!choices.fillers) },
                compact = true,
                modifier = Modifier
                    .width(CHOICE_WIDTH.dp)
                    .testTag("cut-fillers"),
            )
            ChoiceCard(
                title = stringResource(R.string.captions_cut_pauses),
                subtitle = stringResource(
                    R.string.captions_long_pauses,
                    summary.longPauses,
                    seconds(choices.thresholdUs),
                ),
                selected = choices.pauses,
                onClick = { actions.onPauses(!choices.pauses) },
                compact = true,
                modifier = Modifier
                    .width(CHOICE_WIDTH.dp)
                    .testTag("cut-pauses"),
            )
            LabelledSlider(
                label = stringResource(R.string.captions_pauses_longer),
                value = choices.thresholdUs / 1e6f,
                onValueChange = { actions.onThreshold((it * 10).toInt() * 100_000L) },
                valueText = stringResource(R.string.captions_seconds, seconds(choices.thresholdUs)),
                valueRange = 0.3f..2f,
                labelWidth = 140.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .testTag("pause-threshold"),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            state.sources.forEach { source -> SourceText(source, state, actions.onToken) }
        }
        val project = state.project ?: return@Column
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = length(project.durationUs),
                style = type.meta,
                color = colors.textTertiary,
                textDecoration = if (summary.cuts > 0) TextDecoration.LineThrough else null,
            )
            Text(
                text = length(project.durationUs - summary.cutUs),
                style = type.metaStrong,
                color = colors.textPrimary,
                modifier = Modifier.testTag("new-length"),
            )
            Text(
                text = if (summary.cuts == 0) {
                    stringResource(R.string.captions_no_cuts)
                } else {
                    pluralStringResource(
                        R.plurals.captions_cuts,
                        summary.cuts,
                        summary.cuts,
                        seconds(summary.cutUs),
                    )
                },
                style = type.caption,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            if (summary.cuts > 0) {
                SecondaryButton(
                    text = stringResource(R.string.captions_restore_all),
                    onClick = actions.onRestoreAll,
                    modifier = Modifier.testTag("restore-all"),
                )
            }
            PrimaryButton(
                text = stringResource(R.string.captions_make_cuts),
                onClick = actions.onDone,
                compact = true,
                enabled = summary.cuts > 0,
                modifier = Modifier.testTag("text-done"),
            )
        }
    }
}

private const val CHOICE_WIDTH = 170
