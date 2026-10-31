package tech.idct.clipra.feature.effects

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.filter
import tech.idct.clipra.feature.effects.common.PickerCategory
import tech.idct.clipra.feature.effects.common.TILE_HEIGHT
import tech.idct.clipra.feature.effects.common.clipLabel
import tech.idct.clipra.feature.effects.filters.FilterCategory
import tech.idct.clipra.feature.effects.filters.FiltersViewModel
import tech.idct.clipra.feature.effects.filters.Grid
import tech.idct.clipra.feature.effects.stack.EffectStackActions
import tech.idct.clipra.feature.effects.stack.EffectStackList
import tech.idct.clipra.feature.effects.stack.EffectStackUiState
import tech.idct.clipra.feature.effects.stack.EffectStackViewModel

/**
 * The Filters library in the tablet editor (#70, TabletEffects board): the 24 filters by
 * category, as thumbnails of the selected clip at the playhead; a tap gives it the filter.
 * Its strength is in the effect stack beside.
 */
@Composable
fun FiltersLibraryPanel(
    clipId: String?,
    playheadUs: Long,
    modifier: Modifier = Modifier,
    viewModel: FiltersViewModel = hiltViewModel(key = "tablet-filters"),
) {
    LaunchedEffect(clipId) { viewModel.select(clipId, playheadUs) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val density = LocalDensity.current
    LaunchedEffect(state.clip?.id) {
        if (state.clip == null) return@LaunchedEffect
        // Three across the 300 dp pane.
        val height = with(density) { TILE_HEIGHT.roundToPx() }
        val width = with(density) { 86.dp.roundToPx() }
        viewModel.loadThumbnails(width, height)
    }
    FiltersLibrary(
        category = state.category,
        selected = state.clip?.filter?.type,
        thumbnails = state.thumbnails,
        enabled = state.clip != null,
        onCategory = viewModel::category,
        onPick = viewModel::pick,
        modifier = modifier,
    )
}

/** The filters by [category]; with nothing to filter ([enabled] false) a hint stands instead. */
@Composable
internal fun FiltersLibrary(
    category: FilterCategory,
    selected: String?,
    thumbnails: Map<String, Bitmap>,
    enabled: Boolean,
    onCategory: (FilterCategory) -> Unit,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("filters-library"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.effects_title_filters),
                style = type.bodyStrong,
                color = colors.textPrimary,
            )
            Text(
                text = Filters.all.size.toString(),
                style = type.meta,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = stringResource(R.string.effects_tap_to_apply),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterCategory.entries.forEach { entry ->
                val (label, count) = when (entry) {
                    FilterCategory.All -> R.string.effects_category_all to Filters.all.size

                    FilterCategory.Looks -> R.string.effects_category_looks to Filters.looks.size

                    FilterCategory.Stylized ->
                        R.string.effects_category_stylized to Filters.stylized.size
                }
                PickerCategory(
                    label = stringResource(label),
                    count = count,
                    selected = entry == category,
                    onClick = { onCategory(entry) },
                    modifier = Modifier.testTag("category:${entry.name}"),
                )
            }
        }
        if (enabled) {
            Grid(
                category = category,
                selected = selected,
                thumbnails = thumbnails,
                onPick = onPick,
                columns = LIBRARY_COLUMNS,
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            Text(
                text = stringResource(R.string.effects_library_hint),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
    }
}

/**
 * The effect stack in the tablet editor (#70, TabletEffects board), for the selected clip: its
 * effects in order with their strengths, masks and switches, Add, and Copy and Paste. A slider
 * is committed when it is let go, and the editor's preview shows it.
 */
@Composable
fun EffectStackPanel(
    clipId: String,
    modifier: Modifier = Modifier,
    viewModel: EffectStackViewModel = hiltViewModel(key = "tablet-stack"),
) {
    LaunchedEffect(clipId) { viewModel.select(clipId) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    if (state.clip?.id != clipId) {
        Box(modifier)
        return
    }
    // A slider's value while it moves; let go, it is committed.
    var live by remember(clipId) { mutableStateOf<Map<Int, Float>>(emptyMap()) }
    var block by remember(clipId) { mutableStateOf<Float?>(null) }
    val effects = state.effects.mapIndexed { index, effect ->
        val strength = live[index] ?: effect.strength
        val sized = if (effect.type == "mosaic" && block != null) {
            effect.params + (Filters.MOSAIC_SIZE to block!!)
        } else {
            effect.params
        }
        effect.copy(strength = strength, params = sized)
    }
    EffectStack(
        state = state,
        effects = effects,
        actions = EffectStackActions(
            onBack = {},
            onAdd = viewModel::add,
            onMove = viewModel::move,
            onEnabled = viewModel::setEnabled,
            onStrengthLive = { index, strength -> live = live + (index to strength) },
            onStrengthDone = { index ->
                live[index]?.let { viewModel.setStrength(index, it) }
                live = live - index
            },
            onBlockSizeLive = { block = it },
            onBlockSizeDone = {
                block?.let(viewModel::setBlockSize)
                block = null
            },
            onRemove = viewModel::remove,
            onCopy = viewModel::copy,
            onPaste = viewModel::paste,
        ),
        modifier = modifier,
    )
}

/** The stack of [state]'s clip: a header with Add, the effect cards, and Copy and Paste. */
@Composable
internal fun EffectStack(
    state: EffectStackUiState,
    effects: List<Effect>,
    actions: EffectStackActions,
    modifier: Modifier = Modifier,
    initialAdding: Boolean = false,
) {
    val project: Project = state.project ?: return
    val clip: Clip = state.clip ?: return
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    var adding by rememberSaveable(clip.id) { mutableStateOf(initialAdding) }
    Column(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .testTag("effect-stack"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.effects_stack_title),
                style = type.bodyStrong,
                color = colors.textPrimary,
            )
            Text(
                text = clipLabel(project, clip),
                style = type.meta,
                color = colors.textSecondary,
                maxLines = 1,
                modifier = Modifier.weight(1f),
            )
            // Add, as the board's "+": the picker opens below the cards.
            IconAction(
                icon = ClipraIcons.Add,
                contentDescription = stringResource(R.string.effects_stack_add_description),
                onClick = { adding = !adding },
                enabled = state.addable.isNotEmpty(),
                tint = if (adding) colors.accent else colors.textPrimary,
                modifier = Modifier
                    .clip(ClipraTheme.shapes.button)
                    .background(colors.surfaceRaised)
                    .testTag("stack-add"),
            )
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            EffectStackList(state, effects, actions, adding, onAdded = { adding = false })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = stringResource(R.string.effects_stack_copy),
                onClick = actions.onCopy,
                enabled = effects.isNotEmpty(),
                modifier = Modifier.weight(1f),
            )
            SecondaryButton(
                text = stringResource(R.string.effects_stack_paste),
                onClick = actions.onPaste,
                enabled = state.copied != null,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/** Three filters across, as the TabletEffects board. */
private const val LIBRARY_COLUMNS = 3
