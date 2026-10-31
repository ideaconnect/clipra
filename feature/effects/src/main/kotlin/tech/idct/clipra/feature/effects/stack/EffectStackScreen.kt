package tech.idct.clipra.feature.effects.stack

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.ClipraSwitch
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.ToolScaffold
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.feature.effects.R
import tech.idct.clipra.feature.effects.common.clipLabel
import tech.idct.clipra.feature.effects.filters.filterName
import kotlin.math.roundToInt

/** What the effect stack board can do. The "live" calls follow a slider; the others commit. */
class EffectStackActions(
    val onBack: () -> Unit,
    val onAdd: (type: String) -> Unit,
    val onMove: (from: Int, to: Int) -> Unit,
    val onEnabled: (index: Int, enabled: Boolean) -> Unit,
    val onStrengthLive: (index: Int, strength: Float) -> Unit,
    val onStrengthDone: (index: Int) -> Unit,
    val onBlockSizeLive: (Float) -> Unit,
    val onBlockSizeDone: () -> Unit,
    val onRemove: (index: Int) -> Unit,
    val onCopy: () -> Unit,
    val onPaste: () -> Unit,
)

/**
 * The effect stack (#29; the tablet's Effects panel, as a board on the phone): the clip's
 * effects as numbered cards in the order they apply, each turned on or off, with its strength
 * (and Mosaic's block size), moved up or down or removed; Add effect, and Copy and Paste
 * between clips.
 */
@Composable
fun EffectStackScreen(
    state: EffectStackUiState,
    effects: List<Effect>,
    actions: EffectStackActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initialAdding: Boolean = false,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val asset = clip.source?.let { project.asset(it.mediaId) }
    var adding by rememberSaveable { mutableStateOf(initialAdding) }
    ToolScaffold(
        header = {
            ScreenHeader(
                title = stringResource(R.string.effects_stack_title),
                subtitle = pluralStringResource(
                    R.plurals.effects_stack_subtitle,
                    effects.size,
                    clipLabel(project, clip),
                    effects.size,
                ),
                navigation = HeaderNavigation.Back,
                onNavigate = actions.onBack,
                actions = {
                    PrimaryButton(
                        text = stringResource(R.string.effects_done),
                        onClick = actions.onBack,
                        compact = true,
                        modifier = Modifier.testTag("stack-done"),
                    )
                },
            )
        },
        preview = {
            val ratio = project.format.ratio
            Box(
                modifier = Modifier
                    .aspectRatio(
                        ratio.width.toFloat() / ratio.height,
                        matchHeightConstraintsFirst = true,
                    )
                    .testTag("preview"),
            ) {
                preview()
            }
        },
        controls = { fill ->
            Column(
                modifier = fill
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                EffectStackList(state, effects, actions, adding, onAdded = { adding = false })
            }
        },
        previewHeight = PREVIEW_HEIGHT,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:EffectStack"),
        bottom = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(top = 8.dp, bottom = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SecondaryButton(
                    text = stringResource(R.string.effects_stack_add),
                    onClick = { adding = !adding },
                    enabled = state.addable.isNotEmpty(),
                    outlined = adding,
                    modifier = Modifier
                        .weight(1.3f)
                        .testTag("stack-add"),
                )
                SecondaryButton(
                    text = stringResource(R.string.effects_stack_copy),
                    onClick = actions.onCopy,
                    enabled = effects.isNotEmpty(),
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stack-copy"),
                )
                SecondaryButton(
                    text = stringResource(R.string.effects_stack_paste),
                    onClick = actions.onPaste,
                    enabled = state.copied != null,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("stack-paste"),
                )
            }
        },
    )
}

/**
 * The stack's cards in the order the effects apply, and the add picker while [adding]; [onAdded]
 * closes it once an effect is picked.
 */
@Composable
internal fun EffectStackList(
    state: EffectStackUiState,
    effects: List<Effect>,
    actions: EffectStackActions,
    adding: Boolean,
    onAdded: () -> Unit,
) {
    val clip = state.clip ?: return
    if (effects.isEmpty()) {
        Text(
            text = stringResource(R.string.effects_stack_empty),
            style = ClipraTheme.typography.body,
            color = ClipraTheme.colors.textSecondary,
            modifier = Modifier.testTag("stack-empty"),
        )
    }
    effects.forEachIndexed { index, effect ->
        EffectCard(clip, effects, index, effect, actions)
    }
    if (adding) {
        AddPicker(state.addable) { type ->
            onAdded()
            actions.onAdd(type)
        }
    }
}

/** One effect of the stack: its place, name, switch, strength and how to move or remove it. */
@Composable
private fun EffectCard(
    clip: Clip,
    effects: List<Effect>,
    index: Int,
    effect: Effect,
    actions: EffectStackActions,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("effect:${effect.type}"),
        shape = ClipraTheme.shapes.card,
        color = colors.surface,
        border = BorderStroke(1.dp, colors.borderSubtle),
    ) {
        Column(modifier = Modifier.padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = (index + 1).toString(),
                    style = type.meta,
                    color = colors.textSecondary,
                    modifier = Modifier.width(22.dp),
                )
                val name = filterName(effect.type)
                Text(
                    text = if (effect.type in Filters.looks) {
                        stringResource(R.string.effects_stack_look, name)
                    } else {
                        name
                    },
                    style = type.bodyStrong,
                    color = if (effect.enabled) colors.textPrimary else colors.textDisabled,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                )
                if (masked(clip, effects, index)) {
                    Text(
                        text = stringResource(R.string.effects_stack_masked),
                        style = type.caption,
                        color = colors.textSecondary,
                        modifier = Modifier.padding(end = 10.dp),
                    )
                }
                ClipraSwitch(
                    checked = effect.enabled,
                    onCheckedChange = { actions.onEnabled(index, it) },
                    compact = true,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .testTag("effect-on:${effect.type}"),
                )
            }
            LabelledSlider(
                label = stringResource(R.string.effects_strength),
                value = effect.strength,
                onValueChange = { actions.onStrengthLive(index, it) },
                onValueChangeFinished = { actions.onStrengthDone(index) },
                valueText = (effect.strength * 100).roundToInt().toString(),
                enabled = effect.enabled,
                labelWidth = 84.dp,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .height(44.dp),
            )
            if (effect.type == "mosaic") {
                val size = effect.params[Filters.MOSAIC_SIZE] ?: Filters.DEFAULT_MOSAIC_SIZE
                LabelledSlider(
                    label = stringResource(R.string.effects_stack_block),
                    value = size,
                    onValueChange = actions.onBlockSizeLive,
                    onValueChangeFinished = actions.onBlockSizeDone,
                    valueText = stringResource(
                        R.string.effects_stack_percent,
                        (size * 100).roundToInt(),
                    ),
                    valueRange = 0.02f..0.2f,
                    enabled = effect.enabled,
                    labelWidth = 84.dp,
                    modifier = Modifier
                        .padding(end = 8.dp)
                        .height(44.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.weight(1f))
                IconAction(
                    icon = ClipraIcons.ChevronDown,
                    contentDescription = stringResource(R.string.effects_stack_up),
                    onClick = { actions.onMove(index, index - 1) },
                    enabled = index > 0,
                    modifier = Modifier
                        .rotate(180f)
                        .testTag("effect-up:${effect.type}"),
                )
                IconAction(
                    icon = ClipraIcons.ChevronDown,
                    contentDescription = stringResource(R.string.effects_stack_down),
                    onClick = { actions.onMove(index, index + 1) },
                    enabled = index < effects.lastIndex,
                    modifier = Modifier.testTag("effect-down:${effect.type}"),
                )
                IconAction(
                    icon = ClipraIcons.Delete,
                    contentDescription = stringResource(R.string.effects_stack_remove),
                    onClick = { actions.onRemove(index) },
                    tint = colors.danger,
                    modifier = Modifier.testTag("effect-remove:${effect.type}"),
                )
            }
        }
    }
}

/** The effects the clip doesn't have yet, Looks then Stylized; picking one adds it on top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AddPicker(addable: List<String>, onPick: (String) -> Unit) {
    val colors = ClipraTheme.colors
    Column(
        modifier = Modifier.testTag("stack-picker"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            R.string.effects_category_looks to Filters.looks,
            R.string.effects_category_stylized to Filters.stylized,
        ).forEach { (label, types) ->
            val offered = types.filter { it in addable }
            if (offered.isEmpty()) return@forEach
            Text(
                text = stringResource(label),
                style = ClipraTheme.typography.sectionLabel,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                offered.forEach { type ->
                    ClipraChip(
                        label = filterName(type),
                        selected = false,
                        onClick = { onPick(type) },
                        modifier = Modifier.testTag("add:$type"),
                    )
                }
            }
        }
    }
}

/** Whether the clip's mask limits effect [index]: the type it names, else the first filter. */
private fun masked(clip: Clip, effects: List<Effect>, index: Int): Boolean {
    val mask = clip.mask?.takeIf { it.shape != MaskShape.None } ?: return false
    val named = mask.effect ?: effects.firstOrNull { Filters.isFilter(it.type) }?.type
    return effects[index].type == named
}

private val PREVIEW_HEIGHT = 240.dp
