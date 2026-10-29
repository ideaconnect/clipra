package tech.idct.clipra.core.designsystem.component

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens

/** One option of a [SegmentedControl]: a label and an optional detail line in Geist Mono. */
@Immutable
data class Segment(val label: String, val detail: String? = null, val enabled: Boolean = true)

/** How the selected segment looks: accent fill, or a light fill for tabs. */
enum class SegmentedStyle { Accent, Tabs }

/**
 * A row of mutually exclusive options in a sunken container: 44 dp segments, the selected one
 * filled. [monospace] sets the labels in Geist Mono, for measurements such as frame rates.
 */
@Composable
fun SegmentedControl(
    segments: List<Segment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    style: SegmentedStyle = SegmentedStyle.Accent,
    monospace: Boolean = false,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = modifier
            .clip(ClipraTheme.shapes.segmentContainer)
            .background(colors.surface)
            .padding(4.dp)
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        segments.forEachIndexed { index, segment ->
            val selected = index == selectedIndex
            val fill = when {
                !selected -> null
                style == SegmentedStyle.Accent -> colors.accent
                else -> colors.textPrimary
            }
            val content = when {
                !segment.enabled -> colors.textDisabled
                selected -> colors.onAccent
                else -> colors.textPrimary
            }
            Column(
                modifier = Modifier
                    .weight(1f)
                    .heightIn(min = ClipraDimens.SegmentHeight)
                    .clip(ClipraTheme.shapes.segment)
                    .then(if (fill != null) Modifier.background(fill) else Modifier)
                    .selectable(
                        selected = selected,
                        enabled = segment.enabled,
                        role = Role.RadioButton,
                        onClick = { onSelect(index) },
                    )
                    .padding(horizontal = 4.dp, vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                val labelStyle = if (monospace) type.metaStrong else type.bodyStrong
                // Long labels (and longer translations) shrink to fit rather than being cut.
                BasicText(
                    text = segment.label,
                    style = labelStyle.copy(color = content, textAlign = TextAlign.Center),
                    maxLines = 1,
                    autoSize = TextAutoSize.StepBased(
                        minFontSize = 11.sp,
                        maxFontSize = labelStyle.fontSize,
                    ),
                )
                if (segment.detail != null) {
                    Text(
                        text = segment.detail,
                        style = type.meta,
                        color = if (selected) colors.onAccentMuted else colors.textSecondary,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * A selectable card for a few rich options (aspect ratio, codec): 56 dp or taller, the selected
 * one with an accent border on a warm fill. Disabled cards stay visible and say why in
 * [subtitle]. [compact] tightens the padding for narrow cards, three to a row.
 */
@Composable
fun ChoiceCard(
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
    monospaceTitle: Boolean = false,
    accentTitleWhenSelected: Boolean = true,
    compact: Boolean = false,
    leading: (@Composable (selected: Boolean) -> Unit)? = null,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val shape = ClipraTheme.shapes.button
    Row(
        modifier = modifier
            .heightIn(min = 56.dp)
            .clip(shape)
            .background(if (selected) colors.accentSelectedBackground else colors.surface)
            .border(ClipraDimens.BorderWidth, if (selected) colors.accent else colors.border, shape)
            .selectable(
                selected = selected,
                enabled = enabled,
                role = Role.RadioButton,
                onClick = onClick,
            )
            .padding(
                horizontal = if (compact) 10.dp else 14.dp,
                vertical = if (compact) 8.dp else 10.dp,
            ),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
    ) {
        leading?.invoke(selected)
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val titleStyle = if (monospaceTitle) type.metaStrong else type.bodyStrong
            // Long titles (and longer translations) shrink to fit rather than being cut.
            BasicText(
                text = title,
                style = titleStyle.copy(
                    color = when {
                        !enabled -> colors.textDisabled
                        selected && accentTitleWhenSelected -> colors.accent
                        else -> colors.textPrimary
                    },
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(
                    minFontSize = 11.sp,
                    maxFontSize = titleStyle.fontSize,
                ),
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = if (compact) type.toolLabel else type.label,
                    color = if (enabled) colors.textSecondary else colors.textDisabled,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * How a selected [ClipraChip] looks: accent fill, warm fill with an accent border, or only an
 * accent border (the background colours of New project).
 */
enum class ChipStyle { Accent, Outline, Border }

/** A pill choice, 36 dp visible with a 44 dp touch area. */
@Composable
fun ClipraChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selectedStyle: ChipStyle = ChipStyle.Outline,
    enabled: Boolean = true,
    role: Role = Role.RadioButton,
    leading: (@Composable RowScope.() -> Unit)? = null,
) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.chip
    val accentFill = selected && selectedStyle == ChipStyle.Accent
    val fill = when {
        accentFill -> colors.accent
        selected && selectedStyle == ChipStyle.Outline -> colors.accentSelectedBackground
        else -> colors.surface
    }
    val border = when {
        accentFill -> colors.accent
        selected -> colors.accent
        else -> colors.border
    }
    val content = when {
        !enabled -> colors.textDisabled
        accentFill -> colors.onAccent
        else -> colors.textPrimary
    }
    Row(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .heightIn(min = ClipraDimens.ChipHeight)
            .clip(shape)
            .background(fill)
            .border(ClipraDimens.BorderWidth, border, shape)
            .selectable(selected = selected, enabled = enabled, role = role, onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        leading?.invoke(this)
        Text(text = label, style = ClipraTheme.typography.chip, color = content, maxLines = 1)
    }
}

/**
 * The Clipra switch: a 48 × 28 track (44 × 26 [compact]) and a 22 dp knob. On is an accent
 * track with a dark knob. It always has switch semantics and a 44 dp touch area.
 */
@Composable
fun ClipraSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    compact: Boolean = false,
) {
    val toggle = if (onCheckedChange != null) {
        Modifier.toggleable(
            value = checked,
            enabled = enabled,
            role = Role.Switch,
            onValueChange = onCheckedChange,
        )
    } else {
        Modifier
    }
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .then(toggle),
        contentAlignment = Alignment.Center,
    ) {
        SwitchTrack(checked = checked, enabled = enabled, compact = compact)
    }
}

@Composable
private fun SwitchTrack(checked: Boolean, enabled: Boolean, compact: Boolean) {
    val colors = ClipraTheme.colors
    val width = if (compact) ClipraDimens.SwitchTrackWidthCompact else ClipraDimens.SwitchTrackWidth
    val height =
        if (compact) ClipraDimens.SwitchTrackHeightCompact else ClipraDimens.SwitchTrackHeight
    val inset: Dp = (height - ClipraDimens.SwitchKnob) / 2
    val knobX by animateDpAsState(
        targetValue = if (checked) width - ClipraDimens.SwitchKnob - inset else inset,
        label = "switch knob",
    )
    Box(
        modifier = Modifier
            .size(width, height)
            .clip(CircleShape)
            .background(
                when {
                    !enabled -> colors.surfaceRaised
                    checked -> colors.accent
                    else -> colors.border
                },
            ),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset(knobX.roundToPx(), 0) }
                .size(ClipraDimens.SwitchKnob)
                .clip(CircleShape)
                .background(
                    when {
                        !enabled -> colors.textDisabled
                        checked -> colors.onAccent
                        else -> colors.textSecondaryAlt
                    },
                ),
        )
    }
}

/** A setting row: title, optional explanation and a switch; the whole row toggles. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    enabled: Boolean = true,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = modifier
            .heightIn(min = ClipraDimens.TouchTarget)
            .toggleable(
                value = checked,
                enabled = enabled,
                role = Role.Switch,
                onValueChange = onCheckedChange,
            )
            .then(if (enabled) Modifier else Modifier.semantics { disabled() }),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = title,
                style = type.body,
                color = if (enabled) colors.textPrimary else colors.textDisabled,
            )
            if (subtitle != null) {
                Text(text = subtitle, style = type.caption, color = colors.textSecondary)
            }
        }
        Spacer(Modifier.width(12.dp))
        SwitchTrack(checked = checked, enabled = enabled, compact = false)
    }
}

/**
 * A slider with its label on the left and the value in Geist Mono on the right.
 * [onValueChangeFinished] marks the end of a drag, for example to make it one undo step.
 */
@Composable
fun LabelledSlider(
    label: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueText: String,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    labelWidth: Dp = 72.dp,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = label,
            style = type.body,
            color = colors.textSecondaryAlt,
            modifier = Modifier.width(labelWidth),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            enabled = enabled,
            onValueChangeFinished = onValueChangeFinished,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = colors.textPrimary,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.border,
                activeTickColor = colors.onAccent,
                inactiveTickColor = colors.textTertiary,
            ),
        )
        Text(
            text = valueText,
            style = type.meta.copy(fontSize = type.chip.fontSize),
            color = colors.textPrimary,
            modifier = Modifier
                .width(56.dp)
                .padding(start = 8.dp),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
    }
}

@Preview(widthDp = 390, backgroundColor = 0xFF0D0D0F, showBackground = true)
@Composable
private fun SelectionPreview() {
    ClipraTheme {
        SelectionSample()
    }
}

@Composable
internal fun SelectionSample() {
    val colors = ClipraTheme.colors
    Column(
        modifier = Modifier.padding(ClipraDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SegmentedControl(
            segments = listOf(
                Segment("HD", "720p"),
                Segment("FHD", "1080p"),
                Segment("2K", "1440p"),
                Segment("4K", "2160p"),
            ),
            selectedIndex = 1,
            onSelect = {},
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedControl(
            segments = listOf("24", "25", "30", "50", "60").map { Segment(it) },
            selectedIndex = 2,
            onSelect = {},
            monospace = true,
            modifier = Modifier.fillMaxWidth(),
        )
        SegmentedControl(
            segments = listOf(Segment("Captions"), Segment("Words"), Segment("Style")),
            selectedIndex = 0,
            onSelect = {},
            style = SegmentedStyle.Tabs,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceCard(
                title = "H.264",
                subtitle = "Plays everywhere",
                selected = true,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            ChoiceCard(
                title = "HEVC",
                subtitle = "Smaller files",
                selected = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
            ChoiceCard(
                title = "AV1",
                subtitle = "Not on this phone",
                selected = false,
                enabled = false,
                onClick = {},
                modifier = Modifier.weight(1f),
            )
        }
        ChoiceCard(
            title = "9:16",
            subtitle = "Reels",
            selected = true,
            monospaceTitle = true,
            onClick = {},
            leading = { selected ->
                ClipraIcon(
                    icon = ClipraIcons.Video,
                    contentDescription = null,
                    tint = if (selected) colors.accent else colors.textSecondary,
                )
            },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ClipraChip(label = "Black", selected = true, onClick = {})
            ClipraChip(label = "White", selected = false, onClick = {})
            ClipraChip(
                label = "Burn in",
                selected = true,
                selectedStyle = ChipStyle.Accent,
                onClick = {},
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ClipraSwitch(checked = true, onCheckedChange = {})
            ClipraSwitch(checked = false, onCheckedChange = {})
            ClipraSwitch(checked = true, onCheckedChange = {}, compact = true)
            ClipraSwitch(checked = false, onCheckedChange = {}, enabled = false)
        }
        SwitchRow(
            title = "Normalize loudness",
            subtitle = "Every clip to -14 LUFS",
            checked = true,
            onCheckedChange = {},
        )
        LabelledSlider(label = "Volume", value = 0.8f, onValueChange = {}, valueText = "80%")
    }
}
