package tech.idct.clipra.feature.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.engine.render.TextRenderer
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.TextAlign
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextSpec
import android.graphics.Color as AndroidColor
import tech.idct.clipra.core.fonts.R as FontsR

/** What the Text screen can do. */
class TextActions(
    val onBack: () -> Unit,
    val onDraft: (String) -> Unit,
    val onSpec: (TextSpec) -> Unit,
)

/**
 * The Text board (#31): the clip in the preview with the title-safe guide, its words edited
 * inline, the font and alignment, the card colour (none for text over video), the text colour,
 * the size and the weight. [draft] is the text being typed, committed a moment after typing
 * stops.
 */
@Composable
fun TextScreen(
    state: TextUiState,
    draft: String,
    actions: TextActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val spec = clip.text ?: return
    val (layer, _) = project.findClip(clip.id) ?: return
    val output = project.format.outputSize
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Text"),
    ) {
        ScreenHeader(
            title = stringResource(
                if (clip.type == ClipType.TextCard) R.string.text_card else R.string.text_overlay,
            ),
            subtitle = stringResource(
                R.string.text_subtitle,
                project.layers.indexOf(layer) + 1,
                timecode(clip.startUs),
                timecode(clip.endUs),
            ),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.text_done),
                    onClick = actions.onBack,
                    compact = true,
                    modifier = Modifier.testTag("text-done"),
                )
            },
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp)
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
                    .border(1.dp, colors.border)
                    .testTag("preview"),
            ) {
                preview()
                SafeArea()
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 12.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(draft, actions.onDraft, Modifier.weight(1f))
                FontButton(spec, onFont = { actions.onSpec(spec.copy(font = it)) })
                AlignButton(spec.align, onAlign = { actions.onSpec(spec.copy(align = it)) })
            }
            Swatches(
                title = stringResource(R.string.text_card_color),
                selected = spec.background,
                allowNone = true,
                onPick = { actions.onSpec(spec.copy(background = it)) },
                tag = "card-color",
            )
            Swatches(
                title = stringResource(R.string.text_color),
                selected = spec.color,
                allowNone = false,
                onPick = { it?.let { color -> actions.onSpec(spec.copy(color = color)) } },
                tag = "text-color",
            )
            var size by remember(spec.size) { mutableFloatStateOf(spec.size) }
            LabelledSlider(
                label = stringResource(R.string.text_size),
                value = size,
                onValueChange = { size = it },
                onValueChangeFinished = { actions.onSpec(spec.copy(size = size)) },
                valueText = stringResource(R.string.text_pixels, (size * output.height).toInt()),
                valueRange = 0.02f..0.2f,
                labelWidth = 64.dp,
                modifier = Modifier
                    .height(48.dp)
                    .testTag("text-size"),
            )
            val weights = listOf(
                400 to R.string.text_weight_regular,
                500 to R.string.text_weight_medium,
                700 to R.string.text_weight_bold,
                800 to R.string.text_weight_heavy,
            )
            SegmentedControl(
                segments = weights.map { Segment(stringResource(it.second)) },
                selectedIndex = weights.indexOfFirst { it.first == spec.weight }.coerceAtLeast(0),
                onSelect = { actions.onSpec(spec.copy(weight = weights[it].first)) },
                style = SegmentedStyle.Tabs,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("text-weight"),
            )
        }
    }
}

/** The title-safe area: text stays inside this guide. */
@Composable
private fun SafeArea() {
    val color = ClipraTheme.colors.textTertiary
    Canvas(modifier = Modifier.fillMaxSize().testTag("safe-area")) {
        val inset = (1 - TextRenderer.SAFE_WIDTH) / 2
        val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
        drawRect(
            color = color,
            topLeft = Offset(size.width * inset, size.height * inset),
            size = androidx.compose.ui.geometry.Size(
                size.width * (1 - 2 * inset),
                size.height * (1 - 2 * inset),
            ),
            style = Stroke(width = 1.dp.toPx(), pathEffect = dash),
        )
    }
}

@Composable
private fun TextField(text: String, onText: (String) -> Unit, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val label = stringResource(R.string.text_label)
    Row(
        modifier = modifier
            .height(48.dp)
            .clip(ClipraTheme.shapes.button)
            .background(colors.surface)
            .border(1.5.dp, colors.border, ClipraTheme.shapes.button)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(text = label, style = type.caption, color = colors.textSecondary)
        BasicTextField(
            value = text,
            onValueChange = onText,
            singleLine = false,
            maxLines = 3,
            textStyle = type.body.copy(color = colors.textPrimary),
            cursorBrush = SolidColor(colors.accent),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Default),
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = label }
                .testTag("text-input"),
        )
    }
}

/** "Aa" in the clip's font; opens the fonts. */
@Composable
private fun FontButton(spec: TextSpec, onFont: (String) -> Unit) {
    val colors = ClipraTheme.colors
    var open by remember { mutableStateOf(false) }
    Box {
        Surface(
            onClick = { open = true },
            modifier = Modifier
                .size(48.dp)
                .border(1.5.dp, colors.border, ClipraTheme.shapes.button)
                .semantics { contentDescription = spec.font }
                .testTag("text-font"),
            shape = ClipraTheme.shapes.button,
            color = colors.surface,
            contentColor = colors.textPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = "Aa",
                    style = ClipraTheme.typography.sectionTitle.copy(
                        fontFamily = family(spec.font, 800),
                    ),
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            TextRenderer.families.forEach { name ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = name,
                            style = ClipraTheme.typography.body.copy(
                                fontFamily = family(name, 600),
                            ),
                        )
                    },
                    onClick = {
                        open = false
                        onFont(name)
                    },
                )
            }
        }
    }
}

/** The bundled font [name] at [weight], for showing it in the menu. */
@Composable
private fun family(name: String, weight: Int): FontFamily {
    val resource = when (name) {
        TextRenderer.BRICOLAGE -> FontsR.font.bricolage_grotesque
        TextRenderer.GEIST_MONO -> FontsR.font.geist_mono
        else -> FontsR.font.geist
    }
    return FontFamily(
        Font(
            resource,
            FontWeight(weight),
            variationSettings = FontVariation.Settings(FontVariation.weight(weight)),
        ),
    )
}

/** Cycles start, centre and end. */
@Composable
private fun AlignButton(align: TextAlign, onAlign: (TextAlign) -> Unit) {
    val colors = ClipraTheme.colors
    val (icon, label) = when (align) {
        TextAlign.Start -> ClipraIcons.AlignLeft to R.string.text_align_start
        TextAlign.Center -> ClipraIcons.AlignCenter to R.string.text_align_center
        TextAlign.End -> ClipraIcons.AlignRight to R.string.text_align_end
    }
    Surface(
        onClick = { onAlign(TextAlign.entries[(align.ordinal + 1) % TextAlign.entries.size]) },
        modifier = Modifier
            .size(48.dp)
            .border(1.5.dp, colors.border, ClipraTheme.shapes.button)
            .testTag("text-align"),
        shape = ClipraTheme.shapes.button,
        color = colors.surface,
        contentColor = colors.textPrimary,
    ) {
        Box(contentAlignment = Alignment.Center) {
            ClipraIcon(icon = icon, contentDescription = stringResource(label))
        }
    }
}

/**
 * A row of colour swatches, the chosen one ringed; [allowNone] adds "None" first (text over
 * video), and the last opens a custom colour.
 */
@Composable
private fun Swatches(
    title: String,
    selected: Long?,
    allowNone: Boolean,
    onPick: (Long?) -> Unit,
    tag: String,
) {
    val colors = ClipraTheme.colors
    var custom by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = ClipraTheme.typography.sectionLabel,
            color = colors.textSecondary,
        )
        // 44 dp targets side by side; eight fit a 390 dp phone, and more scroll.
        Row(modifier = Modifier.horizontalScroll(rememberScrollState()).testTag(tag)) {
            if (allowNone) {
                Swatch(
                    fill = null,
                    selected = selected == null,
                    description = stringResource(R.string.text_color_none),
                    onClick = { onPick(null) },
                )
            }
            val names = listOf(
                R.string.text_color_black,
                R.string.text_color_white,
                R.string.text_color_yellow,
                R.string.text_color_red,
                R.string.text_color_blue,
                R.string.text_color_green,
            )
            TextColors.palette.zip(names).forEach { (color, name) ->
                Swatch(
                    fill = SolidColor(Color(color)),
                    selected = selected == color,
                    description = stringResource(name),
                    onClick = { onPick(color) },
                )
            }
            val isCustom = selected != null && selected !in TextColors.palette
            Swatch(
                fill = if (isCustom) {
                    SolidColor(
                        Color(selected),
                    )
                } else {
                    Brush.sweepGradient(colors.colorWheel)
                },
                selected = isCustom,
                description = stringResource(R.string.text_color_custom),
                onClick = { custom = true },
                plus = !isCustom,
            )
        }
    }
    if (custom) {
        CustomColor(
            initial = selected ?: TextColors.YELLOW,
            onPick = {
                custom = false
                onPick(it)
            },
            onDismiss = { custom = false },
        )
    }
}

@Composable
private fun Swatch(
    fill: Brush?,
    selected: Boolean,
    description: String,
    onClick: () -> Unit,
    plus: Boolean = false,
) {
    val colors = ClipraTheme.colors
    Box(
        modifier = Modifier
            .size(ClipraDimens.TouchTarget)
            .clip(CircleShape)
            .border(2.dp, if (selected) colors.accent else Color.Transparent, CircleShape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .then(if (fill != null) Modifier.background(fill) else Modifier)
                .border(1.dp, colors.borderStrong, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            when {
                fill == null -> ClipraIcon(
                    icon = ClipraIcons.MaskNone,
                    contentDescription = null,
                    tint = colors.textSecondary,
                    size = 18.dp,
                )

                plus -> ClipraIcon(
                    icon = ClipraIcons.Add,
                    contentDescription = null,
                    tint = colors.background,
                    size = 16.dp,
                )
            }
        }
    }
}

/** A custom colour: hue and shade. */
@Composable
private fun CustomColor(initial: Long, onPick: (Long) -> Unit, onDismiss: () -> Unit) {
    val hsv = FloatArray(3).also { AndroidColor.colorToHSV(initial.toInt(), it) }
    var hue by remember { mutableFloatStateOf(hsv[0]) }
    var shade by remember { mutableFloatStateOf(hsv[2].coerceAtLeast(0.2f)) }
    val color = AndroidColor.HSVToColor(floatArrayOf(hue, 0.85f, shade)).toLong() and 0xFFFFFFFF
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = ClipraTheme.colors.surfaceRaised,
        title = { Text(stringResource(R.string.text_color_custom)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                        .clip(ClipraTheme.shapes.button)
                        .background(Color(color)),
                )
                LabelledSlider(
                    label = stringResource(R.string.text_hue),
                    value = hue,
                    onValueChange = { hue = it },
                    valueText = hue.toInt().toString(),
                    valueRange = 0f..359f,
                )
                LabelledSlider(
                    label = stringResource(R.string.text_shade),
                    value = shade,
                    onValueChange = { shade = it },
                    valueText = (shade * 100).toInt().toString(),
                    valueRange = 0.2f..1f,
                )
            }
        },
        confirmButton = {
            TextAction(text = stringResource(R.string.text_use_color), onClick = { onPick(color) })
        },
        dismissButton = {
            TextAction(
                text = stringResource(R.string.text_cancel),
                onClick = onDismiss,
                color = ClipraTheme.colors.textSecondary,
            )
        },
    )
}

/** "00:11.00". */
private fun timecode(us: Long): String {
    val hundredths = us / 10_000
    return "%02d:%02d.%02d".format(hundredths / 6_000, (hundredths / 100) % 60, hundredths % 100)
}
