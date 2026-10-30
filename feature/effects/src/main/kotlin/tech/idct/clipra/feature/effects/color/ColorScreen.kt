package tech.idct.clipra.feature.effects.color

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.ColorMath
import tech.idct.clipra.core.model.HslShift
import tech.idct.clipra.core.model.HueRange
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.transformAt
import tech.idct.clipra.feature.effects.R
import tech.idct.clipra.feature.effects.masks.FrameBox
import kotlin.math.roundToInt

/** The tabs of the Color board. */
enum class ColorTab { Adjust, Hsl, Lut }

/** What the Color board can do. The "live" call follows a slider; the others commit. */
class ColorActions(
    val onBack: () -> Unit,
    val onLive: (ColorGrade) -> Unit,
    val onGrade: (ColorGrade) -> Unit,
    /** A slider let go: commits what [onLive] last showed. */
    val onDone: () -> Unit,
    val onSplit: (Float) -> Unit,
    val onAuto: () -> Unit,
    val onReset: () -> Unit,
    val onPaste: () -> Unit,
    val onImport: () -> Unit,
    val onDismissError: () -> Unit = {},
)

/**
 * The Color board (#28): the clip at the editor's playhead split into before and after (drag
 * the divider), and three tabs: Adjust (six sliders), HSL (eight hue ranges) and LUT (built-in
 * looks and imported `.cube` files, with strength). Auto, Reset and Paste to the other clips sit
 * at the bottom.
 */
@Composable
fun ColorScreen(
    state: ColorUiState,
    grade: ColorGrade,
    split: Float,
    actions: ColorActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ColorTab = ColorTab.Adjust,
    initialRange: HueRange = HueRange.Oranges,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val (layer, _) = project.findClip(clip.id) ?: return
    val asset = clip.source?.let { project.asset(it.mediaId) }
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var range by rememberSaveable { mutableStateOf(initialRange) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Color"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.effects_color_title),
            subtitle = stringResource(
                R.string.effects_color_subtitle,
                asset?.displayName.orEmpty(),
                project.layers.indexOf(layer) + 1,
            ),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.effects_done),
                    onClick = actions.onBack,
                    compact = true,
                    modifier = Modifier.testTag("color-done"),
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
            SplitOverlay(state, asset?.displaySize ?: Size(0, 0), split, actions.onSplit)
        }
        SegmentedControl(
            segments = listOf(
                Segment(stringResource(R.string.effects_color_tab_adjust)),
                Segment(stringResource(R.string.effects_color_tab_hsl)),
                Segment(stringResource(R.string.effects_color_tab_lut)),
            ),
            selectedIndex = tab.ordinal,
            onSelect = { tab = ColorTab.entries[it] },
            style = SegmentedStyle.Tabs,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .fillMaxWidth()
                .testTag("color-tabs"),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 8.dp),
        ) {
            when (tab) {
                ColorTab.Adjust -> AdjustTab(grade, actions)
                ColorTab.Hsl -> HslTab(grade, range, { range = it }, actions)
                ColorTab.Lut -> LutTab(state, grade, actions)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SecondaryButton(
                text = stringResource(R.string.effects_color_auto),
                onClick = actions.onAuto,
                modifier = Modifier
                    .weight(1f)
                    .testTag("color-auto"),
            )
            SecondaryButton(
                text = stringResource(R.string.effects_color_reset),
                onClick = actions.onReset,
                modifier = Modifier
                    .weight(1f)
                    .testTag("color-reset"),
            )
            PasteButton(state.pasteTargets, actions.onPaste, Modifier.weight(1.45f))
        }
    }
}

/** "Before" and "After" either side of a divider across the clip, dragged sideways. */
@Composable
private fun SplitOverlay(
    state: ColorUiState,
    source: Size,
    split: Float,
    onSplit: (Float) -> Unit,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val transform = clip.transformAt(state.playheadUs - clip.startUs)
    val cell = Cells.rect(project.format, clip.cell)
    val frame = Cells.size(project.format.outputSize, cell)
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val width = with(density) { maxWidth.toPx() }
        val height = with(density) { maxHeight.toPx() }
        val box = FrameBox.of(width, height, project.format.outputSize, cell)
        val top = box.view(ClipGeometry.toFrame(transform, frame, source, split, 0f))
        val bottom = box.view(ClipGeometry.toFrame(transform, frame, source, split, 1f))
        val corner = box.view(ClipGeometry.toFrame(transform, frame, source, 0f, 0f))
        val far = box.view(ClipGeometry.toFrame(transform, frame, source, 1f, 0f))
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(transform, frame, source, width, height) {
                    detectDragGestures { change, _ ->
                        val point = box.frame(change.position)
                        val u = ClipGeometry.toSource(transform, frame, source, point).x
                        onSplit(u.coerceIn(0f, 1f))
                    }
                }
                .testTag("color-split"),
        ) {
            drawLine(colors.textPrimary, top, bottom, 2.dp.toPx())
        }
        val middle = Offset((top.x + bottom.x) / 2, (top.y + bottom.y) / 2)
        val handle = 40.dp
        val handlePx = with(density) { handle.toPx() }
        Box(
            modifier = Modifier
                .offset {
                    IntOffset(
                        (middle.x - handlePx / 2).roundToInt(),
                        (middle.y - handlePx / 2).roundToInt(),
                    )
                }
                .size(handle)
                .clip(CircleShape)
                .background(colors.textPrimary),
            contentAlignment = Alignment.Center,
        ) {
            ClipraIcon(
                icon = ClipraIcons.CompareArrows,
                contentDescription = null,
                tint = colors.background,
                size = 20.dp,
            )
        }
        val inset = with(density) { 6.dp.toPx() }
        Chip(
            text = stringResource(R.string.effects_color_before),
            color = colors.textPrimary,
            modifier = Modifier.offset {
                IntOffset((corner.x + inset).roundToInt(), (corner.y + inset).roundToInt())
            },
        )
        val afterWidth = with(density) { 56.dp.toPx() }
        Chip(
            text = stringResource(R.string.effects_color_after),
            color = colors.accent,
            modifier = Modifier.offset {
                IntOffset((far.x - inset - afterWidth).roundToInt(), (far.y + inset).roundToInt())
            }.width(56.dp),
            alignEnd = true,
        )
    }
}

@Composable
private fun Chip(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
) {
    Box(
        modifier = modifier,
        contentAlignment = if (alignEnd) Alignment.TopEnd else Alignment.TopStart,
    ) {
        Text(
            text = text,
            style = ClipraTheme.typography.chip,
            color = color,
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(ClipraTheme.colors.background.copy(alpha = 0.85f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** Exposure, Contrast, Saturation, Temperature, Tint and Vignette, -100 to +100 each. */
@Composable
private fun AdjustTab(grade: ColorGrade, actions: ColorActions) {
    val sliders = listOf(
        Triple(R.string.effects_color_exposure, grade.exposure) { v: Float ->
            grade.copy(exposure = v)
        },
        Triple(R.string.effects_color_contrast, grade.contrast) { v: Float ->
            grade.copy(contrast = v)
        },
        Triple(R.string.effects_color_saturation, grade.saturation) { v: Float ->
            grade.copy(saturation = v)
        },
        Triple(R.string.effects_color_temperature, grade.temperature) { v: Float ->
            grade.copy(temperature = v)
        },
        Triple(R.string.effects_color_tint, grade.tint) { v: Float -> grade.copy(tint = v) },
        Triple(R.string.effects_color_vignette, grade.vignette) { v: Float ->
            grade.copy(vignette = v)
        },
    )
    sliders.forEachIndexed { i, (label, value, with) ->
        AmountSlider(
            label = stringResource(label),
            value = value,
            onChange = { actions.onLive(with(it)) },
            onDone = actions.onDone,
            modifier = Modifier.testTag("color-adjust:$i"),
        )
    }
}

/** The eight hue ranges as swatches, then hue, saturation and luminance for the picked one. */
@Composable
private fun HslTab(
    grade: ColorGrade,
    range: HueRange,
    onRange: (HueRange) -> Unit,
    actions: ColorActions,
) {
    val colors = ClipraTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp)
            .testTag("color-hues"),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        HueRange.entries.forEach { hue ->
            val rgb = ColorMath.hslToRgb(hue.degrees, SWATCH_SATURATION, SWATCH_LIGHTNESS)
            val chosen = hue == range
            val name = stringResource(hueName(hue))
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .border(2.dp, if (chosen) colors.accent else Color.Transparent, CircleShape)
                    .clickable(role = Role.RadioButton) { onRange(hue) }
                    .semantics {
                        selected = chosen
                        contentDescription = name
                    }
                    .padding(5.dp)
                    .clip(CircleShape)
                    .background(Color(rgb[0], rgb[1], rgb[2]))
                    .testTag("hue:${hue.name}"),
            )
        }
    }
    Text(
        text = stringResource(
            R.string.effects_color_hsl_hint,
            stringResource(hueName(range)).lowercase(),
        ),
        style = ClipraTheme.typography.caption,
        color = colors.textSecondary,
        modifier = Modifier.padding(top = 14.dp, bottom = 6.dp),
    )
    val shift = grade.hsl[range] ?: HslShift()
    fun with(changed: HslShift) = grade.copy(hsl = grade.hsl + (range to changed))
    AmountSlider(
        label = stringResource(R.string.effects_color_hue),
        value = shift.hue,
        onChange = { actions.onLive(with(shift.copy(hue = it))) },
        onDone = actions.onDone,
        modifier = Modifier.testTag("color-hsl:hue"),
    )
    AmountSlider(
        label = stringResource(R.string.effects_color_saturation),
        value = shift.saturation,
        onChange = { actions.onLive(with(shift.copy(saturation = it))) },
        onDone = actions.onDone,
        modifier = Modifier.testTag("color-hsl:saturation"),
    )
    AmountSlider(
        label = stringResource(R.string.effects_color_luminance),
        value = shift.luminance,
        onChange = { actions.onLive(with(shift.copy(luminance = it))) },
        onDone = actions.onDone,
        modifier = Modifier.testTag("color-hsl:luminance"),
    )
}

/** None, the built-in looks and the imported files; Import .cube; the strength. */
@Composable
private fun LutTab(state: ColorUiState, grade: ColorGrade, actions: ColorActions) {
    val colors = ClipraTheme.colors
    Column(
        modifier = Modifier.padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LutRow(
            name = stringResource(R.string.effects_color_lut_none),
            note = null,
            swatch = emptyList(),
            selected = grade.lut == null,
            onClick = { actions.onGrade(grade.copy(lut = null)) },
            modifier = Modifier.testTag("lut:none"),
        )
        state.luts.forEach { lut ->
            LutRow(
                name = lut.name ?: stringResource(builtInName(lut.id)),
                note = stringResource(
                    if (lut.builtIn) {
                        R.string.effects_color_lut_built_in
                    } else {
                        R.string.effects_color_lut_imported
                    },
                ),
                swatch = lut.swatch,
                selected = grade.lut == lut.id,
                onClick = { actions.onGrade(grade.copy(lut = lut.id)) },
                modifier = Modifier.testTag("lut:${lut.id}"),
            )
        }
        if (state.importFailed) {
            Text(
                text = stringResource(R.string.effects_color_lut_failed),
                style = ClipraTheme.typography.caption,
                color = colors.danger,
                modifier = Modifier
                    .clickable(onClick = actions.onDismissError)
                    .testTag("lut-failed"),
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            ImportButton(actions.onImport)
            Spacer(Modifier.width(16.dp))
            LabelledSlider(
                label = stringResource(R.string.effects_color_strength),
                value = grade.lutStrength,
                onValueChange = { actions.onLive(grade.copy(lutStrength = it)) },
                onValueChangeFinished = actions.onDone,
                valueText = stringResource(
                    R.string.effects_color_percent,
                    (grade.lutStrength * 100).roundToInt(),
                ),
                enabled = grade.lut != null,
                labelWidth = 72.dp,
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("lut-strength"),
            )
        }
    }
}

@Composable
private fun LutRow(
    name: String,
    note: String?,
    swatch: List<Int>,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 44.dp)
            .semantics { this.selected = selected },
        shape = shape,
        color = if (selected) colors.accentSelectedBackground else colors.surface,
        border = BorderStroke(1.dp, if (selected) colors.accent else colors.border),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val fill = when (swatch.size) {
                0 -> Brush.linearGradient(listOf(colors.surfaceRaised, colors.surfaceRaised))
                1 -> Brush.linearGradient(listOf(Color(swatch[0]), Color(swatch[0])))
                else -> Brush.horizontalGradient(swatch.map { Color(it) })
            }
            Box(
                modifier = Modifier
                    .size(width = 28.dp, height = 16.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(fill),
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = name,
                style = ClipraTheme.typography.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            if (note != null) {
                Text(
                    text = note,
                    style = ClipraTheme.typography.caption,
                    color = colors.textSecondary,
                )
            }
        }
    }
}

/** "+ Import .cube", dashed, as the design draws it. */
@Composable
private fun ImportButton(onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    Box(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clip(shape)
            .clickable(role = Role.Button, onClick = onClick)
            .testTag("lut-import"),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            val width = 1.5.dp.toPx()
            val stroke = Stroke(
                width = width,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())),
            )
            // Inside the bounds, so the clip doesn't cut the line.
            drawRoundRect(
                color = colors.borderStrong,
                topLeft = Offset(width / 2, width / 2),
                size = androidx.compose.ui.geometry.Size(size.width - width, size.height - width),
                style = stroke,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()),
            )
        }
        Text(
            text = stringResource(R.string.effects_color_lut_import),
            style = ClipraTheme.typography.bodyStrong,
            color = colors.textPrimary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
        )
    }
}

/** "Paste to 4 clips": outlined in the accent; off when there is nowhere to paste. */
@Composable
private fun PasteButton(targets: Int, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    val enabled = targets > 0
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.5.dp, if (enabled) colors.accent else colors.border, shape)
            .clickable(role = Role.Button, enabled = enabled, onClick = onClick)
            .testTag("color-paste"),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = androidx.compose.ui.res.pluralStringResource(
                R.plurals.effects_color_paste,
                targets,
                targets,
            ),
            style = ClipraTheme.typography.bodyStrong,
            color = if (enabled) colors.accent else colors.textDisabled,
            maxLines = 1,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    }
}

/** A -100 to +100 slider showing "+10", "0" or "-8". */
@Composable
private fun AmountSlider(
    label: String,
    value: Float,
    onChange: (Float) -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val rounded = value.roundToInt()
    LabelledSlider(
        label = label,
        value = value,
        onValueChange = { onChange(it.roundToInt().toFloat()) },
        onValueChangeFinished = onDone,
        valueText = if (rounded > 0) "+$rounded" else rounded.toString(),
        valueRange = -100f..100f,
        labelWidth = 104.dp,
        modifier = modifier.height(48.dp),
    )
}

private fun hueName(range: HueRange): Int = when (range) {
    HueRange.Reds -> R.string.effects_color_hue_reds
    HueRange.Oranges -> R.string.effects_color_hue_oranges
    HueRange.Yellows -> R.string.effects_color_hue_yellows
    HueRange.Greens -> R.string.effects_color_hue_greens
    HueRange.Aquas -> R.string.effects_color_hue_aquas
    HueRange.Blues -> R.string.effects_color_hue_blues
    HueRange.Purples -> R.string.effects_color_hue_purples
    HueRange.Magentas -> R.string.effects_color_hue_magentas
}

private fun builtInName(id: String): Int = when (id.substringAfter(':')) {
    "teal_orange" -> R.string.effects_color_lut_teal_orange
    "warm_film" -> R.string.effects_color_lut_warm_film
    "cool_night" -> R.string.effects_color_lut_cool_night
    "silver_mono" -> R.string.effects_color_lut_silver_mono
    else -> R.string.effects_color_lut_faded
}

private val PREVIEW_HEIGHT = 300.dp

// The HSL swatches show each range's centre hue.
private const val SWATCH_SATURATION = 0.75f
private const val SWATCH_LIGHTNESS = 0.55f
