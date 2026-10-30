package tech.idct.clipra.feature.effects.masks

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChoiceCard
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.ClipraSwitch
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.keyframeAt
import tech.idct.clipra.core.model.maskKeyframed
import tech.idct.clipra.core.model.transformAt
import tech.idct.clipra.feature.effects.R
import tech.idct.clipra.feature.effects.filters.filterName
import kotlin.math.roundToInt

/** What the Masks screen can do. The "live" calls follow a slider or a drag; the others commit. */
class MasksActions(
    val onBack: () -> Unit,
    val onShape: (MaskShape) -> Unit,
    val onMaskLive: (Mask) -> Unit,
    val onMask: (Mask) -> Unit,
    val onMaskDone: () -> Unit,
    val onBlockSizeLive: (Float) -> Unit,
    val onBlockSizeDone: () -> Unit,
    /** Adds a mask keyframe at the playhead, or deletes the one there (#21). */
    val onKeyframe: () -> Unit = {},
    /** Follow face (#52) on or off. */
    val onFollowFace: (Boolean) -> Unit = {},
)

/**
 * The Masks board (#27): the mask on the preview with its handles, the shape, the effect it
 * limits, Invert, Mosaic's block size, the feather and, for rectangles, the corner radius.
 * [mask] and [blockSize] follow a drag or a slider while it moves.
 */
@Composable
fun MasksScreen(
    state: MasksUiState,
    mask: Mask?,
    blockSize: Float,
    actions: MasksActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val shape = mask?.shape ?: MaskShape.None
    val effectType = mask?.effect ?: state.effectType
    val effectName = filterName(effectType)
    val outputShortSide = minOf(project.format.outputSize.width, project.format.outputSize.height)
    val asset = clip.source?.let { project.asset(it.mediaId) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Masks"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.effects_mask_title),
            subtitle = stringResource(
                R.string.effects_mask_subtitle,
                stringResource(R.string.effects_mask_effect, effectName),
                asset?.displayName.orEmpty(),
            ),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.effects_done),
                    onClick = actions.onBack,
                    compact = true,
                    modifier = Modifier.testTag("masks-done"),
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clipToBounds()
                .background(colors.ad.background),
            contentAlignment = Alignment.Center,
        ) {
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
            if (mask != null && shape != MaskShape.None) {
                MaskOverlay(
                    mask = mask,
                    transform = clip.transformAt(state.playheadUs - clip.startUs),
                    frame = project.format.outputSize,
                    source = asset?.displaySize ?: Size(0, 0),
                    chip = chipText(mask, outputShortSide, state.tracking),
                    onChange = actions.onMaskLive,
                    onCommit = actions.onMask,
                    cell = Cells.rect(project.format, clip.cell),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Triple(MaskShape.None, R.string.effects_mask_none, ClipraIcons.MaskNone),
                    Triple(
                        MaskShape.Rectangle,
                        R.string.effects_mask_rectangle,
                        ClipraIcons.MaskRectangle,
                    ),
                    Triple(MaskShape.Oval, R.string.effects_mask_oval, ClipraIcons.MaskOval),
                ).forEach { (option, label, icon) ->
                    ChoiceCard(
                        title = stringResource(label),
                        selected = shape == option,
                        onClick = { actions.onShape(option) },
                        compact = true,
                        leading = { selected ->
                            ClipraIcon(
                                icon = icon,
                                contentDescription = null,
                                tint = if (selected) colors.accent else colors.textPrimary,
                            )
                        },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("shape:${option.name}"),
                    )
                }
            }
            if (mask != null && shape != MaskShape.None) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    AppliesTo(
                        effectName = effectName,
                        choices = clip.effects.map { it.type }.filter(Filters::isFilter)
                            .ifEmpty { listOf(MasksUiState.DEFAULT_EFFECT) },
                        onPick = { actions.onMask(mask.copy(effect = it)) },
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        text = stringResource(R.string.effects_mask_invert),
                        style = ClipraTheme.typography.bodyStrong,
                        color = colors.textPrimary,
                    )
                    ClipraSwitch(
                        checked = mask.invert,
                        onCheckedChange = { actions.onMask(mask.copy(invert = it)) },
                        modifier = Modifier.testTag("mask-invert"),
                    )
                }
            }
            if (effectType == "mosaic" && clip.effects.any { it.type == "mosaic" }) {
                LabelledSlider(
                    label = stringResource(R.string.effects_mask_block_size),
                    value = blockSize,
                    onValueChange = actions.onBlockSizeLive,
                    onValueChangeFinished = actions.onBlockSizeDone,
                    valueText = pixels(blockSize, outputShortSide),
                    valueRange = 0.02f..0.2f,
                    labelWidth = 104.dp,
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("mask-block-size"),
                )
            }
            if (mask != null && shape != MaskShape.None) {
                LabelledSlider(
                    label = stringResource(R.string.effects_mask_feather),
                    value = mask.feather,
                    onValueChange = { actions.onMaskLive(mask.copy(feather = it)) },
                    onValueChangeFinished = actions.onMaskDone,
                    valueText = pixels(mask.feather, outputShortSide),
                    valueRange = 0f..0.15f,
                    labelWidth = 104.dp,
                    modifier = Modifier
                        .height(52.dp)
                        .testTag("mask-feather"),
                )
                if (shape == MaskShape.Rectangle) {
                    LabelledSlider(
                        label = stringResource(R.string.effects_mask_radius),
                        value = mask.cornerRadius,
                        onValueChange = { actions.onMaskLive(mask.copy(cornerRadius = it)) },
                        onValueChangeFinished = actions.onMaskDone,
                        valueText = pixels(mask.cornerRadius, outputShortSide),
                        valueRange = 0f..0.25f,
                        labelWidth = 104.dp,
                        modifier = Modifier
                            .height(52.dp)
                            .testTag("mask-radius"),
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    FollowFace(
                        on = mask.followFace || state.tracking != null,
                        tracking = state.tracking,
                        noFace = state.noFace,
                        onFollowFace = actions.onFollowFace,
                        modifier = Modifier.weight(1f),
                    )
                    MaskKeyframe(clip, state.playheadUs, actions.onKeyframe)
                }
            }
        }
    }
}

/**
 * Follow face (#52): a switch with how it stands, "Tracked on the phone, frame by frame", how
 * far the tracking is, or that no face was found.
 */
@Composable
private fun FollowFace(
    on: Boolean,
    tracking: Float?,
    noFace: Boolean,
    onFollowFace: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    // The whole card toggles, as a switch row does.
    Row(
        modifier = modifier
            .height(64.dp)
            .clip(ClipraTheme.shapes.button)
            .background(colors.surface)
            .toggleable(value = on, role = Role.Switch, onValueChange = onFollowFace)
            .padding(start = 12.dp, end = 4.dp)
            .testTag("mask-follow-face"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.effects_mask_follow_face),
                style = ClipraTheme.typography.bodyStrong,
                color = colors.textPrimary,
            )
            Text(
                text = when {
                    tracking != null -> stringResource(
                        R.string.effects_mask_tracking,
                        (tracking * 100).roundToInt(),
                    )

                    noFace -> stringResource(R.string.effects_mask_no_face)

                    else -> stringResource(R.string.effects_mask_follow_face_note)
                },
                style = ClipraTheme.typography.caption,
                color = if (noFace && tracking == null) colors.danger else colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.testTag("mask-follow-note"),
            )
        }
        ClipraSwitch(checked = on, onCheckedChange = null)
    }
}

/** A keyframe at the playhead (Masks board, #21): add one, or delete the one there. */
@Composable
private fun MaskKeyframe(clip: Clip, playheadUs: Long, onKeyframe: () -> Unit) {
    val colors = ClipraTheme.colors
    val time = playheadUs - clip.startUs
    val keyed = clip.maskKeyframed?.keyframeAt(time) != null
    val label = if (keyed) {
        R.string.effects_mask_keyframe_delete
    } else {
        R.string.effects_mask_keyframe_add
    }
    val description = stringResource(label, timecode(playheadUs))
    Box(
        modifier = Modifier
            .size(64.dp)
            .clip(ClipraTheme.shapes.button)
            .background(if (keyed) colors.accentSelectedBackground else Color.Transparent)
            .border(1.5.dp, colors.accent, ClipraTheme.shapes.button)
            .clickable(
                role = Role.Button,
                enabled = time in 0..clip.durationUs,
                onClickLabel = description,
                onClick = onKeyframe,
            )
            .semantics { contentDescription = description }
            .testTag("mask-keyframe"),
        contentAlignment = Alignment.Center,
    ) {
        ClipraIcon(
            icon = ClipraIcons.Keyframe,
            contentDescription = null,
            tint = colors.accent,
            size = 20.dp,
        )
    }
}

/** 00:07.12, as the editor's transport shows the playhead. */
private fun timecode(us: Long): String {
    val hundredths = us / 10_000
    return "%02d:%02d.%02d".format(
        java.util.Locale.ROOT,
        hundredths / 6_000,
        hundredths / 100 % 60,
        hundredths % 100,
    )
}

/** "Applies to: Mosaic effect", with the clip's other filters to choose from. */
@Composable
private fun AppliesTo(
    effectName: String,
    choices: List<String>,
    onPick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 52.dp)
                .border(1.5.dp, colors.border, ClipraTheme.shapes.button)
                .testTag("mask-applies-to"),
            shape = ClipraTheme.shapes.button,
            color = colors.surface,
            contentColor = colors.textPrimary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    text = stringResource(R.string.effects_mask_applies_to),
                    style = ClipraTheme.typography.label,
                    color = colors.textSecondary,
                )
                Text(
                    text = stringResource(R.string.effects_mask_effect, effectName),
                    style = ClipraTheme.typography.bodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                ClipraIcon(icon = ClipraIcons.ChevronDown, contentDescription = null, size = 18.dp)
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            choices.forEach { type ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.effects_mask_effect, filterName(type))) },
                    onClick = {
                        open = false
                        onPick(type)
                    },
                )
            }
        }
    }
}

/** "Inside oval · feather 43", or "Tracking face · feather 12" (#52). */
@Composable
private fun chipText(mask: Mask, outputShortSide: Int, tracking: Float?): String {
    if (tracking != null) {
        return stringResource(R.string.effects_mask_tracking, (tracking * 100).roundToInt())
    }
    val place = stringResource(
        when {
            mask.followFace -> R.string.effects_mask_tracking_face
            mask.shape == MaskShape.Oval && mask.invert -> R.string.effects_mask_outside_oval
            mask.shape == MaskShape.Oval -> R.string.effects_mask_inside_oval
            mask.invert -> R.string.effects_mask_outside_rectangle
            else -> R.string.effects_mask_inside_rectangle
        },
    )
    val feather = (mask.feather * outputShortSide).roundToInt()
    return stringResource(R.string.effects_mask_chip, place, feather)
}

/** A size in fractions of the picture's shorter side, as pixels of the export. */
@Composable
private fun pixels(fraction: Float, outputShortSide: Int): String =
    stringResource(R.string.effects_pixels, (fraction * outputShortSide).roundToInt())

private val PREVIEW_HEIGHT = 330.dp
