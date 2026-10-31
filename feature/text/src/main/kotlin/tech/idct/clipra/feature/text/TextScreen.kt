package tech.idct.clipra.feature.text

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import tech.idct.clipra.core.designsystem.component.ChipStyle
import tech.idct.clipra.core.designsystem.component.ClipraChip
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.component.ToolScaffold
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.engine.render.TextRenderer
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.TextAlign
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextEffects
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TextTemplate
import tech.idct.clipra.core.model.TextTemplates
import android.graphics.Color as AndroidColor
import androidx.compose.ui.text.style.TextAlign as ComposeTextAlign
import tech.idct.clipra.core.fonts.R as FontsR

/** What the Text screen can do. */
class TextActions(
    val onBack: () -> Unit,
    val onDraft: (String) -> Unit,
    val onSpec: (TextSpec) -> Unit,
    /** The text's animation in and out (#33). */
    val onAnimation: (Animation) -> Unit = {},
    /** Templates (#34): apply one, save the clip's look as one, delete a saved one. */
    val onTemplate: (TextTemplate) -> Unit = {},
    val onSaveTemplate: () -> Unit = {},
    val onDeleteTemplate: (String) -> Unit = {},
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
    templates: List<TextTemplate> = TextTemplates.builtIn,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val spec = clip.text ?: return
    val (layer, _) = project.findClip(clip.id) ?: return
    val output = project.format.outputSize
    val card = clip.type == ClipType.TextCard
    val title = stringResource(if (card) R.string.text_card else R.string.text_overlay)
    ToolScaffold(
        header = {
            ScreenHeader(
                title = title,
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
        },
        preview = {
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
        },
        controls = { fill ->
            Column(
                modifier = fill
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 12.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                TextWords(spec, draft, actions)
                TextColorPickers(spec, actions)
                EffectGrid(spec, onEffect = { actions.onSpec(spec.copy(effect = it)) })
                TemplateGrid(
                    spec = spec,
                    templates = templates,
                    onTemplate = actions.onTemplate,
                    onSave = actions.onSaveTemplate,
                    onDelete = actions.onDeleteTemplate,
                )
                TextAnimations(clip.animation ?: Animation(), actions)
                TextSizeAndWeight(spec, output.height, actions)
            }
        },
        previewHeight = 280.dp,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Text"),
    )
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
        // 48 dp targets side by side; seven fit a 390 dp phone, and more scroll.
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

/** The words, with the font and the alignment beside them. */
@Composable
internal fun TextWords(spec: TextSpec, draft: String, actions: TextActions) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextField(draft, actions.onDraft, Modifier.weight(1f))
        FontButton(spec, onFont = { actions.onSpec(spec.copy(font = it)) })
        AlignButton(spec.align, onAlign = { actions.onSpec(spec.copy(align = it)) })
    }
}

/** The card colour (none for text over video) and the text colour. */
@Composable
internal fun TextColorPickers(spec: TextSpec, actions: TextActions) {
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
}

/** Animation in and out (#33). */
@Composable
internal fun TextAnimations(animation: Animation, actions: TextActions) {
    AnimationRow(
        title = stringResource(R.string.text_animation_in),
        choices = Animations.textIn,
        selected = animation.enter,
        onPick = { actions.onAnimation(animation.copy(enter = it)) },
        tag = "text-in",
    )
    AnimationRow(
        title = stringResource(R.string.text_animation_out),
        choices = Animations.textOut,
        selected = animation.exit,
        onPick = { actions.onAnimation(animation.copy(exit = it)) },
        tag = "text-out",
    )
}

/** The size, in pixels of an output [outputHeight] high, and the weight. */
@Composable
internal fun TextSizeAndWeight(spec: TextSpec, outputHeight: Int, actions: TextActions) {
    var size by remember(spec.size) { mutableFloatStateOf(spec.size) }
    LabelledSlider(
        label = stringResource(R.string.text_size),
        value = size,
        onValueChange = { size = it },
        onValueChangeFinished = { actions.onSpec(spec.copy(size = size)) },
        valueText = stringResource(R.string.text_pixels, (size * outputHeight).toInt()),
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

/** "00:11.00". */
private fun timecode(us: Long): String {
    val hundredths = us / 10_000
    return "%02d:%02d.%02d".format(hundredths / 6_000, (hundredths / 100) % 60, hundredths % 100)
}

/**
 * The 12 text effects (#32) as tiles: "Aa" in the clip's font, colours and card, drawn by the
 * renderer that draws the clip, so each tile shows exactly what the effect does.
 */
@Composable
internal fun EffectGrid(spec: TextSpec, onEffect: (String?) -> Unit) {
    val context = LocalContext.current
    val effects = listOf<String?>(null) + TextEffects.all
    val tiles = remember(spec.font, spec.weight, spec.color, spec.background) {
        effects.associateWith { effect ->
            TextRenderer.render(
                context,
                spec.copy(
                    text = "Aa",
                    size = TILE_TEXT,
                    align = TextAlign.Center,
                    background = spec.background ?: TextColors.BLACK,
                    effect = effect,
                ),
                card = true,
                TILE_WIDTH,
                TILE_HEIGHT,
            ).asImageBitmap()
        }
    }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.testTag("text-effects"),
    ) {
        Text(
            text = stringResource(R.string.text_effect),
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
        )
        effects.chunked(EFFECT_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { effect ->
                    EffectTile(
                        name = stringResource(effectName(effect)),
                        tile = tiles.getValue(effect),
                        selected = spec.effect == effect,
                        onClick = { onEffect(effect) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("effect:${effect ?: "none"}"),
                    )
                }
            }
        }
    }
}

@Composable
private fun EffectTile(
    name: String,
    tile: ImageBitmap,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.button
    Column(
        modifier = modifier
            .clip(shape)
            .border(2.dp, if (selected) colors.accent else colors.border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Image(
            bitmap = tile,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(42.dp),
        )
        Text(
            text = name,
            style = ClipraTheme.typography.caption,
            color = if (selected) colors.accent else colors.textSecondary,
            textAlign = ComposeTextAlign.Center,
            maxLines = 1,
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(vertical = 4.dp),
        )
    }
}

private fun effectName(effect: String?): Int = when (effect) {
    "outline" -> R.string.text_effect_outline
    "shadow" -> R.string.text_effect_shadow
    "glow" -> R.string.text_effect_glow
    "neon" -> R.string.text_effect_neon
    "box" -> R.string.text_effect_box
    "gradient" -> R.string.text_effect_gradient
    "extrude" -> R.string.text_effect_extrude
    "glitch" -> R.string.text_effect_glitch
    "hollow" -> R.string.text_effect_hollow
    "retro" -> R.string.text_effect_retro
    "marker" -> R.string.text_effect_marker
    else -> R.string.text_effect_none
}

private const val EFFECT_COLUMNS = 4

// Tiles are drawn at this size in pixels, "Aa" half their height, as in the design.
private const val TILE_WIDTH = 192
private const val TILE_HEIGHT = 96
private const val TILE_TEXT = 0.46f

/** None plus the text animations (#33) as chips, the picked one filled, as in the design. */
@Composable
private fun AnimationRow(
    title: String,
    choices: List<String>,
    selected: String?,
    onPick: (String?) -> Unit,
    tag: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
        )
        Row(
            modifier = Modifier
                .horizontalScroll(rememberScrollState())
                .testTag(tag),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            (listOf<String?>(null) + choices).forEach { id ->
                ClipraChip(
                    label = stringResource(animationName(id)),
                    selected = id == selected,
                    onClick = { onPick(id) },
                    selectedStyle = ChipStyle.Light,
                    modifier = Modifier.testTag("$tag:${id ?: "none"}"),
                )
            }
        }
    }
}

private fun animationName(id: String?): Int = when (id) {
    "typewriter" -> R.string.text_animation_typewriter
    "fade_up" -> R.string.text_animation_fade_up
    "pop" -> R.string.text_animation_pop
    "slide" -> R.string.text_animation_slide
    "bounce" -> R.string.text_animation_bounce
    "wave" -> R.string.text_animation_wave
    "glitch" -> R.string.text_animation_glitch
    "fade" -> R.string.text_animation_fade
    else -> R.string.text_animation_none
}
