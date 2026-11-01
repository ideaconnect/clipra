package tech.idct.clipra.feature.text

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.delay
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TextTemplate
import tech.idct.clipra.core.model.TextTemplates

/**
 * The Text library in the tablet editor (#70, TabletText board): Add text, the templates and the
 * text effects, beside the editor's preview and timeline. They change the selected text clip;
 * with none selected, a template adds new text in its look at the playhead.
 */
@Composable
fun TextLibraryPanel(
    clipId: String?,
    playheadUs: Long,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TextViewModel = hiltViewModel(key = PANEL_KEY),
) {
    LaunchedEffect(clipId) { viewModel.select(clipId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val samples = templateSamples()
    val newWords = stringResource(R.string.text_new_words)
    val spec = state.clip?.text
    TextLibrary(
        spec = spec,
        templates = templates,
        actions = TextLibraryActions(
            onAdd = {
                val start = TextTemplates.builtIn.first { it.id == "title" }
                viewModel.addText(newWords, start, playheadUs)?.let(onSelect)
            },
            onTemplate = { template ->
                if (spec != null) {
                    viewModel.applyTemplate(template)
                } else {
                    val words = samples[template.id] ?: template.spec.text
                    viewModel.addText(words, template, playheadUs)?.let(onSelect)
                }
            },
            onSave = if (spec != null) viewModel::saveTemplate else null,
            onDelete = viewModel::deleteTemplate,
            onEffect = { effect -> spec?.let { viewModel.setSpec(it.copy(effect = effect)) } },
        ),
        modifier = modifier,
    )
}

/** What the Text library can do (#70). */
class TextLibraryActions(
    val onAdd: () -> Unit,
    /** A template, given to the selected text or as new text when none is selected. */
    val onTemplate: (TextTemplate) -> Unit,
    /** Save as template; null when there is no look to save. */
    val onSave: (() -> Unit)?,
    val onDelete: (String) -> Unit,
    val onEffect: (String?) -> Unit,
)

/** The Text library: Add text, the templates, and the effects of the selected text, [spec]. */
@Composable
internal fun TextLibrary(
    spec: TextSpec?,
    templates: List<TextTemplate>,
    actions: TextLibraryActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("text-library"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.text_overlay),
                style = type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            AddTextButton(actions.onAdd)
        }
        TemplateGrid(
            spec = spec,
            templates = templates,
            onTemplate = actions.onTemplate,
            onSave = actions.onSave,
            onDelete = actions.onDelete,
        )
        if (spec != null) {
            EffectGrid(spec, onEffect = actions.onEffect)
        } else {
            Text(
                text = stringResource(R.string.text_library_hint),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * The Text inspector in the tablet editor (#70, TabletText board), for the selected text clip:
 * its words, font and alignment, colours, animation, size and weight. Typing is committed a
 * moment after it stops, to the clip it was typed into.
 */
@Composable
fun TextInspectorPanel(
    clipId: String,
    modifier: Modifier = Modifier,
    viewModel: TextViewModel = hiltViewModel(key = PANEL_KEY),
) {
    LaunchedEffect(clipId) { viewModel.select(clipId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.project
    val clip = state.clip?.takeIf { it.id == clipId }
    val spec = clip?.text
    if (project == null || clip == null || spec == null) {
        Box(modifier)
        return
    }
    val draft = rememberWords(clip.id, spec.text, viewModel::setWords)
    val actions = TextActions(
        onBack = {},
        onDraft = { draft.value = it },
        onSpec = { viewModel.setSpec(it.copy(text = draft.value)) },
        onAnimation = viewModel::setAnimation,
    )
    TextInspector(project, clip, draft.value, actions, modifier)
}

/**
 * The words being typed into clip [clipId], whose words are [words]: committed a moment after
 * typing stops, and to that clip when the selection moves on. Words changed from outside, by
 * undo or redo, replace the draft, so the old ones aren't written back over them.
 */
@Composable
internal fun rememberWords(
    clipId: String,
    words: String,
    commit: (clipId: String, words: String) -> Unit,
): MutableState<String> {
    val draft = remember(clipId) { mutableStateOf(words) }
    // The clip's words as the draft last matched them.
    val known = remember(clipId) { mutableStateOf(words) }
    LaunchedEffect(clipId, words) {
        if (words != known.value) {
            known.value = words
            draft.value = words
        }
    }
    LaunchedEffect(clipId, draft.value) {
        delay(TYPING_PAUSE_MS)
        known.value = draft.value
        commit(clipId, draft.value)
    }
    // What was typed last lands in its clip when the selection moves on.
    DisposableEffect(clipId) { onDispose { commit(clipId, draft.value) } }
    return draft
}

/** The Text inspector for [clip]: its words ([draft] while typed), look, size and animation. */
@Composable
internal fun TextInspector(
    project: Project,
    clip: Clip,
    draft: String,
    actions: TextActions,
    modifier: Modifier = Modifier,
) {
    val spec = clip.text ?: return
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val (layer, _) = project.findClip(clip.id) ?: return
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("text-inspector"),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(12.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(colors.textPrimary),
            )
            Text(
                text = stringResource(
                    if (clip.type ==
                        ClipType.TextCard
                    ) {
                        R.string.text_card
                    } else {
                        R.string.text_overlay
                    },
                ),
                style = type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(
                    R.string.text_inspector_place,
                    project.layers.indexOf(layer) + 1,
                    clip.durationUs / 1_000_000.0,
                ),
                style = type.meta,
                color = colors.textSecondary,
            )
        }
        TextWords(spec, draft, actions)
        TextColorPickers(spec, actions)
        TextSizeAndWeight(spec, project.format.outputSize.height, actions)
        TextAnimations(clip.animation ?: Animation(), actions)
    }
}

/** "+ Add text", as the media bin's Import: 36 dp to see, 48 dp to touch. */
@Composable
private fun AddTextButton(onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .minimumInteractiveComponentSize()
            .height(36.dp)
            .semantics { role = Role.Button }
            .testTag("add-text"),
        shape = RoundedCornerShape(10.dp),
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClipraIcon(icon = ClipraIcons.Add, contentDescription = null, size = 16.dp)
            Text(
                text = stringResource(R.string.text_add),
                style = ClipraTheme.typography.chip,
            )
        }
    }
}

/** Both panels share one view model in the editor's screen, so they edit the same clip. */
private const val PANEL_KEY = "tablet-text"

private const val TYPING_PAUSE_MS = 600L
