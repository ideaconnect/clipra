package tech.idct.clipra.feature.editor.layers

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.feature.editor.LayerActions
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.timeline.formatTimecode
import kotlin.math.roundToInt

/** The tabs of the Layers board. */
enum class LayerTab { Blend, Transform, Layers }

/** What the Layers board shows: the selected clip, with any values a gesture is changing. */
class LayersUiState(
    val project: Project,
    val clip: Clip,
    val playheadUs: Long,
    val tab: LayerTab,
    val cropping: Boolean,
    val transform: Transform = clip.transform.value,
    val opacity: Float = clip.opacity.value,
)

/** What the Layers board can change. The "live" calls follow a gesture; the others commit. */
class LayersActions(
    val onDone: () -> Unit,
    val onTab: (LayerTab) -> Unit,
    val onCrop: (Boolean) -> Unit,
    val onTransformLive: (Transform) -> Unit,
    val onTransform: (Transform) -> Unit,
    val onBlend: (BlendMode) -> Unit,
    val onOpacityLive: (Float) -> Unit,
    val onOpacityDone: () -> Unit,
    val onSelectLayer: (layerId: String) -> Unit,
    val layers: LayerActions,
    /** Puts the clip into a mosaic cell (#17), or over the whole frame with null. */
    val onCell: (Int?) -> Unit = {},
)

/**
 * The Layers board (#19, #20): the selected clip on the preview with its handles, and three
 * tabs: Blend (mode and opacity), Transform (exact values, fit, fill, flip, crop, reset) and
 * Layers (the layer list). Sound clips have no picture, so they open on Layers.
 */
@Composable
fun LayersScreen(
    state: LayersUiState,
    actions: LayersActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val project = state.project
    val clip = state.clip
    val (layer, _) = project.findClip(clip.id) ?: return
    val layerNumber = project.layers.indexOf(layer) + 1
    val visual = clip.isVisual()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Layers"),
    ) {
        ScreenHeader(
            title = stringResource(
                R.string.editor_layers_header,
                displayName(project, clip),
                layerNumber,
            ),
            subtitle = stringResource(
                R.string.editor_layers_subtitle,
                kindLabel(project, clip),
                formatTimecode(clip.startUs),
                formatTimecode(clip.endUs),
            ),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onDone,
            actions = { DoneButton(actions.onDone) },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(PREVIEW_HEIGHT)
                .clipToBounds()
                .background(colors.ad.background),
            contentAlignment = Alignment.Center,
        ) {
            val format = project.format
            Box(
                modifier = Modifier
                    .aspectRatio(
                        format.ratio.width.toFloat() / format.ratio.height,
                        matchHeightConstraintsFirst = true,
                    )
                    .testTag("preview"),
            ) {
                preview()
            }
            // Over the whole area: a moved clip's handles can sit beyond the frame.
            if (visual && clip.contains(state.playheadUs)) {
                TransformOverlay(
                    transform = state.transform,
                    frame = format.outputSize,
                    source = sourceSize(project, clip),
                    onChange = actions.onTransformLive,
                    onCommit = actions.onTransform,
                    mode = if (state.cropping) OverlayMode.Crop else OverlayMode.Transform,
                    chip = stringResource(
                        R.string.editor_layers_chip,
                        blendLabel(clip.blend),
                        (state.opacity * 100).roundToInt(),
                    ),
                    cell = Cells.rect(format, clip.cell),
                )
            }
        }
        val tabs = listOf(
            Segment(stringResource(R.string.editor_layers_tab_blend), enabled = visual),
            Segment(stringResource(R.string.editor_layers_tab_transform), enabled = visual),
            Segment(stringResource(R.string.editor_layers_tab_layers)),
        )
        val tab = if (visual) state.tab else LayerTab.Layers
        SegmentedControl(
            segments = tabs,
            selectedIndex = tab.ordinal,
            onSelect = { actions.onTab(LayerTab.entries[it]) },
            style = SegmentedStyle.Tabs,
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                .fillMaxWidth()
                .testTag("layer-tabs"),
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 16.dp),
        ) {
            when (tab) {
                LayerTab.Blend -> BlendTab(clip.blend, state.opacity, actions)

                LayerTab.Transform -> {
                    // In a mosaic cell (#17), positions are in the cell's pixels.
                    val cell = Cells.rect(project.format, clip.cell)
                    TransformTab(state, Cells.size(project.format.outputSize, cell), actions)
                }

                LayerTab.Layers -> LayersTab(state, layer.id, actions)
            }
        }
    }
}

@Composable
private fun DoneButton(onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = Modifier
            .heightIn(min = 40.dp)
            .semantics { role = Role.Button }
            .testTag("layers-done"),
        shape = ClipraTheme.shapes.button,
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
    ) {
        Box(
            modifier = Modifier.padding(horizontal = 14.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = stringResource(R.string.editor_layers_done),
                style = ClipraTheme.typography.body.copy(
                    fontWeight = ClipraTheme.typography.bodyStrong.fontWeight,
                ),
            )
        }
    }
}

@Composable
private fun BlendTab(blend: BlendMode, opacity: Float, actions: LayersActions) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Column(
            modifier = Modifier.testTag("blend-modes"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            BlendMode.entries.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { mode ->
                        Option(
                            label = blendLabel(mode),
                            selected = mode == blend,
                            onClick = { actions.onBlend(mode) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("blend:${mode.name}"),
                        )
                    }
                }
            }
        }
        LabelledSlider(
            label = stringResource(R.string.editor_layers_opacity),
            value = opacity,
            onValueChange = actions.onOpacityLive,
            onValueChangeFinished = actions.onOpacityDone,
            valueText = stringResource(
                R.string.editor_layers_percent,
                (opacity * 100).roundToInt(),
            ),
            labelWidth = 60.dp,
            modifier = Modifier
                .height(44.dp)
                .testTag("opacity"),
        )
    }
}

@Composable
private fun TransformTab(state: LayersUiState, frame: Size, actions: LayersActions) {
    val transform = state.transform
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val layout = state.project.format.layout
        if (layout != Layout.Single) CellPicker(layout.cells, state.clip.cell, actions.onCell)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                label = stringResource(R.string.editor_transform_x),
                value = (transform.x * frame.width).roundToInt(),
                unit = stringResource(R.string.editor_transform_px),
                onValue = { actions.onTransform(transform.copy(x = it / frame.width)) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("transform-x"),
            )
            NumberField(
                label = stringResource(R.string.editor_transform_y),
                value = (transform.y * frame.height).roundToInt(),
                unit = stringResource(R.string.editor_transform_px),
                onValue = { actions.onTransform(transform.copy(y = it / frame.height)) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("transform-y"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField(
                label = stringResource(R.string.editor_transform_scale),
                value = (transform.scale * 100).roundToInt(),
                unit = "%",
                onValue = {
                    val scale = (it / 100).coerceIn(
                        MIN_SCALE_PERCENT / 100,
                        MAX_SCALE_PERCENT / 100,
                    )
                    actions.onTransform(transform.copy(scale = scale))
                },
                modifier = Modifier
                    .weight(1f)
                    .testTag("transform-scale"),
            )
            NumberField(
                label = stringResource(R.string.editor_transform_rotation),
                value = transform.rotation.roundToInt(),
                unit = "°",
                onValue = { actions.onTransform(transform.copy(rotation = normalizeDegrees(it))) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("transform-rotation"),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val button = Modifier
                .weight(1f)
                .height(44.dp)
            Option(
                label = stringResource(R.string.editor_transform_fit),
                selected = transform.fit == Fit.Fit,
                onClick = {
                    actions.onTransform(transform.copy(fit = Fit.Fit, x = 0f, y = 0f, scale = 1f))
                },
                modifier = button.testTag("transform-fit"),
                raised = true,
            )
            Option(
                label = stringResource(R.string.editor_transform_fill),
                selected = transform.fit == Fit.Fill,
                onClick = {
                    actions.onTransform(transform.copy(fit = Fit.Fill, x = 0f, y = 0f, scale = 1f))
                },
                modifier = button.testTag("transform-fill"),
                raised = true,
            )
            Option(
                label = stringResource(R.string.editor_transform_flip),
                selected = transform.flipHorizontal,
                onClick = {
                    actions.onTransform(transform.copy(flipHorizontal = !transform.flipHorizontal))
                },
                modifier = button.testTag("transform-flip"),
                raised = true,
            )
            Option(
                label = stringResource(R.string.editor_transform_crop),
                selected = state.cropping,
                onClick = { actions.onCrop(!state.cropping) },
                modifier = button.testTag("transform-crop"),
                raised = true,
            )
            Option(
                label = stringResource(R.string.editor_transform_reset),
                selected = false,
                onClick = { actions.onTransform(Transform()) },
                modifier = button.testTag("transform-reset"),
                raised = true,
            )
        }
        if (state.cropping) {
            Text(
                text = stringResource(R.string.editor_transform_crop_hint),
                style = ClipraTheme.typography.caption,
                color = ClipraTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun LayersTab(state: LayersUiState, layerId: String, actions: LayersActions) {
    val colors = ClipraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.padding(bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_layers_hint),
                style = ClipraTheme.typography.caption,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
            )
            AddLayerButton(actions.layers.onAdd)
        }
        LayerList(
            project = state.project,
            playheadUs = state.playheadUs,
            actions = actions.layers,
            selectedLayerId = layerId,
            onSelect = actions.onSelectLayer,
        )
    }
}

@Composable
private fun AddLayerButton(onClick: () -> Unit) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    Surface(
        onClick = onClick,
        modifier = Modifier
            .height(ClipraDimens.TouchTarget)
            .semantics { role = Role.Button }
            .drawBehind {
                drawOutline(
                    outline = shape.createOutline(size, layoutDirection, this),
                    color = colors.borderStrong,
                    style = Stroke(
                        width = ClipraDimens.BorderWidth.toPx() * 2,
                        pathEffect = PathEffect.dashPathEffect(
                            floatArrayOf(4.dp.toPx(), 3.dp.toPx()),
                        ),
                    ),
                )
            }
            .testTag("add-layer"),
        shape = shape,
        color = Color.Transparent,
        contentColor = colors.textPrimary,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ClipraIcon(icon = ClipraIcons.Add, contentDescription = null, size = 14.dp)
            Text(
                text = stringResource(R.string.editor_layer_add),
                style = ClipraTheme.typography.sectionLabel,
            )
        }
    }
}

/**
 * A choice on a 40 dp tile: blend modes on a sunken tile, transform actions on a raised one.
 * The selected tile has an accent border on a warm fill.
 */
@Composable
private fun Option(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    raised: Boolean = false,
) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.segment
    val type = if (raised) ClipraTheme.typography.sectionLabel else ClipraTheme.typography.navLabel
    val fill = when {
        selected -> colors.accentSelectedBackground
        raised -> colors.surfaceRaised
        else -> colors.surface
    }
    val border = when {
        selected -> colors.accent
        raised -> Color.Transparent
        else -> colors.border
    }
    Surface(
        onClick = onClick,
        modifier = modifier
            .heightIn(min = 40.dp)
            .semantics { this.selected = selected },
        shape = shape,
        color = fill,
        contentColor = if (selected) colors.accent else colors.textPrimary,
        border = androidx.compose.foundation.BorderStroke(ClipraDimens.BorderWidth, border),
    ) {
        Box(modifier = Modifier.padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
            // Longer translations shrink to fit rather than being cut.
            BasicText(
                text = label,
                style = type.copy(
                    color = if (selected) colors.accent else colors.textPrimary,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 9.sp, maxFontSize = type.fontSize),
            )
        }
    }
}

/**
 * A labelled number with its unit (Position X, Scale): typing a value and pressing Done, or
 * leaving the field, applies it.
 */
@Composable
private fun NumberField(
    label: String,
    value: Int,
    unit: String,
    onValue: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val focus = LocalFocusManager.current
    var text by remember { mutableStateOf(value.toString()) }
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(value, focused) { if (!focused) text = value.toString() }
    val valueStyle = type.meta.copy(
        fontSize = type.body.fontSize,
        color = colors.textPrimary,
        textAlign = TextAlign.End,
    )
    Row(
        modifier = modifier
            .height(52.dp)
            .clip(ClipraTheme.shapes.button)
            .background(colors.surface)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(
            text = label,
            style = type.caption,
            color = colors.textSecondary,
            modifier = Modifier.width(64.dp),
            maxLines = 2,
        )
        BasicTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            textStyle = valueStyle,
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Number,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { focus.clearFocus() }),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
                .onFocusChanged { state ->
                    if (focused && !state.isFocused) {
                        parseNumber(text)?.let { if (it.roundToInt() != value) onValue(it) }
                    }
                    focused = state.isFocused
                },
        )
        Text(text = unit, style = valueStyle.copy(color = colors.textSecondary))
    }
}

/** The clip's picture size as shown; text and other generated clips take the frame's. */
internal fun sourceSize(project: Project, clip: Clip): Size =
    clip.source?.let { project.asset(it.mediaId) }?.displaySize ?: project.format.outputSize

/** Clips with a picture: everything but sound. */
internal fun Clip.isVisual(): Boolean = type != ClipType.Audio

@Composable
private fun kindLabel(project: Project, clip: Clip): String {
    val asset = clip.source?.let { project.asset(it.mediaId) }
    return if (asset?.kind == MediaKind.Video && asset.hasAudio && clip.type == ClipType.Video) {
        stringResource(R.string.editor_layers_video_audio)
    } else {
        typeName(clip.type)
    }
}

@Composable
internal fun blendLabel(mode: BlendMode): String = stringResource(
    when (mode) {
        BlendMode.Normal -> R.string.editor_blend_normal
        BlendMode.Multiply -> R.string.editor_blend_multiply
        BlendMode.Screen -> R.string.editor_blend_screen
        BlendMode.Overlay -> R.string.editor_blend_overlay
        BlendMode.SoftLight -> R.string.editor_blend_soft_light
        BlendMode.HardLight -> R.string.editor_blend_hard_light
        BlendMode.Darken -> R.string.editor_blend_darken
        BlendMode.Lighten -> R.string.editor_blend_lighten
        BlendMode.Dodge -> R.string.editor_blend_dodge
        BlendMode.Burn -> R.string.editor_blend_burn
        BlendMode.Add -> R.string.editor_blend_add
        BlendMode.Difference -> R.string.editor_blend_difference
        BlendMode.Exclusion -> R.string.editor_blend_exclusion
        BlendMode.Hue -> R.string.editor_blend_hue
        BlendMode.Color -> R.string.editor_blend_color
        BlendMode.Luminosity -> R.string.editor_blend_luminosity
    },
)

/** A typed number: a comma or a minus sign from other keyboards is accepted too. */
internal fun parseNumber(text: String): Float? =
    text.trim().replace(',', '.').replace('−', '-').toFloatOrNull()?.takeIf { it.isFinite() }

/** Degrees in -180 to 180. */
internal fun normalizeDegrees(degrees: Float): Float {
    var value = degrees % 360f
    if (value > 180f) value -= 360f
    if (value <= -180f) value += 360f
    return value
}

private val PREVIEW_HEIGHT = 340.dp
private const val MIN_SCALE_PERCENT = 1f
private const val MAX_SCALE_PERCENT = 2000f

/** Which mosaic cell the clip is in (#17): the whole frame, or cell 1 to [cells]. */
@Composable
private fun CellPicker(cells: Int, cell: Int?, onCell: (Int?) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .testTag("transform-cells"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.editor_transform_cell),
            style = ClipraTheme.typography.label,
            color = ClipraTheme.colors.textSecondary,
        )
        ClipraChip(
            label = stringResource(R.string.editor_transform_whole_frame),
            selected = cell == null,
            onClick = { onCell(null) },
            modifier = Modifier.testTag("cell-frame"),
        )
        (0 until cells).forEach { index ->
            ClipraChip(
                label = "${index + 1}",
                selected = cell?.mod(cells) == index,
                onClick = { onCell(index) },
                modifier = Modifier.testTag("cell-$index"),
            )
        }
    }
}
