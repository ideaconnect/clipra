package tech.idct.clipra.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChipStyle
import tech.idct.clipra.core.designsystem.component.ChoiceCard
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Background
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Resolution

/**
 * New project (NewProject board): ratio, layout, resolution, frame rate and background, with the
 * output size shown live. [fourKSupported] is false when no encoder takes 2160p frames.
 */
@Composable
fun NewProjectScreen(
    format: Format,
    onFormatChange: (Format) -> Unit,
    onCreate: () -> Unit,
    onBack: () -> Unit,
    fourKSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:NewProject"),
    ) {
        ScreenHeader(title = stringResource(R.string.home_title_new_project), onNavigate = onBack)
        NewProjectForm(
            format = format,
            onFormatChange = onFormatChange,
            fourKSupported = fourKSupported,
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, top = 6.dp, bottom = 16.dp),
        )
        Summary(format, onCreate)
    }
}

/**
 * New project's choices: ratio, layout, resolution, frame rate and background. The phone's
 * New project screen and the tablet home's panel (#71) both show them.
 */
@Composable
internal fun NewProjectForm(
    format: Format,
    onFormatChange: (Format) -> Unit,
    fourKSupported: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Section(stringResource(R.string.home_new_ratio)) {
            RatioGrid(format.ratio) { onFormatChange(format.copy(ratio = it)) }
        }
        Section(
            stringResource(R.string.home_new_layout),
            stringResource(R.string.home_new_layout_note),
        ) {
            LayoutRow(format.layout) { onFormatChange(format.copy(layout = it)) }
        }
        Section(stringResource(R.string.home_new_resolution)) {
            SegmentedControl(
                segments = Resolution.entries.map { Segment(it.label, "${it.shortSide}p") },
                selectedIndex = format.resolution.ordinal,
                onSelect = { onFormatChange(format.copy(resolution = Resolution.entries[it])) },
                modifier = Modifier.fillMaxWidth(),
            )
            if (format.resolution == Resolution.UHD && !fourKSupported) {
                Text(
                    text = stringResource(R.string.home_new_4k_unsupported),
                    style = ClipraTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
        Section(stringResource(R.string.home_new_frame_rate)) {
            SegmentedControl(
                segments = FrameRate.entries.map { Segment(it.fps.toString()) },
                selectedIndex = format.frameRate.ordinal,
                onSelect = { onFormatChange(format.copy(frameRate = FrameRate.entries[it])) },
                monospace = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        Section(
            stringResource(R.string.home_new_background),
            stringResource(R.string.home_new_background_note),
        ) {
            BackgroundRow(format.background) { onFormatChange(format.copy(background = it)) }
        }
    }
}

@Composable
private fun Section(title: String, note: String? = null, content: @Composable () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = buildAnnotatedString {
                append(title)
                if (note != null) {
                    withStyle(type.caption.toSpanStyle().copy(color = colors.textTertiary)) {
                        append(" - $note")
                    }
                }
            },
            style = type.sectionLabel,
            color = colors.textSecondary,
        )
        content()
    }
}

@Composable
private fun RatioGrid(selected: AspectRatio, onSelect: (AspectRatio) -> Unit) {
    val rows = AspectRatio.entries.chunked(3)
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        rows.forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { ratio ->
                    ChoiceCard(
                        title = ratio.label,
                        subtitle = stringResource(ratio.nameRes()),
                        selected = ratio == selected,
                        onClick = { onSelect(ratio) },
                        monospaceTitle = true,
                        accentTitleWhenSelected = false,
                        compact = true,
                        modifier = Modifier
                            .weight(1f)
                            .height(74.dp)
                            .testTag("ratio:${ratio.label}"),
                        leading = { isSelected -> RatioIcon(ratio, isSelected) },
                    )
                }
            }
        }
    }
}

/** The ratio drawn as an outlined frame, 28 dp on its long side. */
@Composable
private fun RatioIcon(ratio: AspectRatio, selected: Boolean) {
    val colors = ClipraTheme.colors
    val scale = 28f / maxOf(ratio.width, ratio.height)
    Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size((ratio.width * scale).dp, maxOf(ratio.height * scale, 12f).dp)
                .border(
                    2.dp,
                    if (selected) colors.accent else colors.textSecondaryAlt,
                    RoundedCornerShape(4.dp),
                ),
        )
    }
}

@Composable
private fun LayoutRow(selected: Layout, onSelect: (Layout) -> Unit) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.selectableGroup(),
    ) {
        Layout.entries.forEach { layout ->
            val isSelected = layout == selected
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(66.dp)
                    .clip(shape)
                    .background(if (isSelected) colors.accentSelectedBackground else colors.surface)
                    .border(
                        ClipraDimens.BorderWidth,
                        if (isSelected) colors.accent else colors.border,
                        shape,
                    )
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = {
                        onSelect(layout)
                    })
                    .testTag("layout:${layout.name}"),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
            ) {
                LayoutCells(
                    layout = layout,
                    portrait = true,
                    width = 16.dp,
                    height = 28.dp,
                    cell = if (isSelected) colors.accent else colors.textSecondaryAlt,
                )
                Text(
                    text = stringResource(layout.labelRes()),
                    style = ClipraTheme.typography.toolLabel,
                    color = colors.textPrimary,
                )
            }
        }
    }
}

/** The layout's cells in a frame: splits stack in portrait and sit side by side in landscape. */
@Composable
internal fun LayoutCells(
    layout: Layout,
    portrait: Boolean,
    width: Dp,
    height: Dp,
    cell: Color,
    modifier: Modifier = Modifier,
) {
    val (rows, columns) = layout.grid(portrait)
    Column(
        modifier = modifier.size(width, height),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        repeat(rows) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                repeat(columns) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxSize()
                            .clip(RoundedCornerShape(2.dp))
                            .background(cell),
                    )
                }
            }
        }
    }
}

/** Rows and columns of a layout; Split 2 and Split 3 stack in portrait frames. */
internal fun Layout.grid(portrait: Boolean): Pair<Int, Int> = when (this) {
    Layout.Single -> 1 to 1
    Layout.Split2 -> if (portrait) 2 to 1 else 1 to 2
    Layout.Split3 -> if (portrait) 3 to 1 else 1 to 3
    Layout.Grid2x2 -> 2 to 2
    Layout.Grid3x3 -> 3 to 3
}

@Composable
private fun BackgroundRow(selected: Background, onSelect: (Background) -> Unit) {
    Row(
        modifier = Modifier
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Background.entries.forEach { background ->
            ClipraChip(
                label = stringResource(background.nameRes()),
                selected = background == selected,
                onClick = { onSelect(background) },
                selectedStyle = ChipStyle.Border,
                modifier = Modifier.testTag("background:${background.name}"),
                leading = { BackgroundSwatch(background) },
            )
        }
    }
}

@Composable
private fun RowScope.BackgroundSwatch(background: Background) {
    val colors = ClipraTheme.colors
    val fill = when (background) {
        Background.Black -> Brush.linearGradient(listOf(Color.Black, Color.Black))

        Background.White -> Brush.linearGradient(listOf(Color.White, Color.White))

        Background.Yellow -> Brush.linearGradient(listOf(colors.accent, colors.accent))

        // Blur takes its colours from the clips: a two-colour swatch stands for that.
        Background.Blur -> Brush.linearGradient(listOf(colors.clip.photo, colors.feature.text))
    }
    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, colors.borderStrong, CircleShape),
    )
}

/** The output size live, and Create project. [inset] is the side padding. */
@Composable
internal fun Summary(format: Format, onCreate: () -> Unit, inset: Dp = 20.dp) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val size = format.outputSize
    Column(modifier = Modifier.background(colors.background)) {
        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
        Column(
            modifier = Modifier.padding(start = inset, end = inset, top = 12.dp, bottom = 22.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
                    val scale = 52f / maxOf(format.ratio.width, format.ratio.height)
                    LayoutCells(
                        layout = format.layout,
                        portrait = format.ratio.height > format.ratio.width,
                        width = (format.ratio.width * scale).dp,
                        height = (format.ratio.height * scale).dp,
                        cell = colors.background,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(colors.textDisabled)
                            .padding(2.dp),
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = stringResource(R.string.home_new_size, size.width, size.height),
                        style = type.metaStrong.copy(fontSize = type.screenTitle.fontSize),
                        color = colors.textPrimary,
                        modifier = Modifier.testTag("output-size"),
                    )
                    Text(
                        text = stringResource(
                            R.string.home_new_summary,
                            format.ratio.label,
                            format.resolution.label,
                            format.frameRate.fps,
                            stringResource(format.layout.labelRes()),
                        ),
                        style = type.label,
                        color = colors.textSecondary,
                    )
                }
            }
            PrimaryButton(
                text = stringResource(R.string.home_new_create),
                onClick = onCreate,
                trailingIcon = ClipraIcons.Forward,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("create-project"),
            )
        }
    }
}

private fun AspectRatio.nameRes(): Int = when (this) {
    AspectRatio.Portrait9x16 -> R.string.home_ratio_reels
    AspectRatio.Landscape16x9 -> R.string.home_ratio_youtube
    AspectRatio.Square1x1 -> R.string.home_ratio_square
    AspectRatio.Portrait4x5 -> R.string.home_ratio_feed
    AspectRatio.Landscape4x3 -> R.string.home_ratio_classic
    AspectRatio.Cinema21x9 -> R.string.home_ratio_cinema
}

private fun Background.nameRes(): Int = when (this) {
    Background.Black -> R.string.home_background_black
    Background.White -> R.string.home_background_white
    Background.Yellow -> R.string.home_background_yellow
    Background.Blur -> R.string.home_background_blur
}

internal fun Layout.labelRes(): Int = when (this) {
    Layout.Single -> R.string.home_layout_single
    Layout.Split2 -> R.string.home_layout_split2
    Layout.Split3 -> R.string.home_layout_split3
    Layout.Grid2x2 -> R.string.home_layout_grid2x2
    Layout.Grid3x3 -> R.string.home_layout_grid3x3
}
