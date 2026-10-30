package tech.idct.clipra.feature.effects.cutout

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import tech.idct.clipra.core.designsystem.component.ToolScaffold
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.model.Behind
import tech.idct.clipra.core.model.ChromaKey
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.feature.effects.R
import kotlin.math.roundToInt

/** What the Cutout board can ask for. */
class CutoutActions(
    val onBack: () -> Unit,
    /** A slider moving: shown at once, not yet an edit. */
    val onLive: (Cutout) -> Unit,
    /** A slider let go, or an option picked: one edit. */
    val onCutout: (Cutout) -> Unit,
    val onRemove: () -> Unit,
    /** Switches to a chroma key (#51), or back to the person cutout. */
    val onChroma: (Boolean) -> Unit = {},
    /** The colour at a point of the clip's own picture (0 to 1 across and down). */
    val onPick: (x: Float, y: Float) -> Long? = { _, _ -> null },
)

/**
 * The Cutout board (#50, #51): the preview with how the search for people stands, Remove
 * background (AI) or Chroma key, what shows behind the subject (the layer below, a blur of the
 * clip, nothing, or a colour), then Edge refine and Hair detail, or the key colour (picked from
 * the clip's Original with the eyedropper), Similarity, Smoothness and Spill removal.
 */
@Composable
fun CutoutScreen(
    state: CutoutUiState,
    cutout: Cutout,
    actions: CutoutActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    initialPicking: Boolean = false,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val project = state.project ?: return
    val clip = state.clip ?: return
    val (layer, _) = project.findClip(clip.id) ?: return
    val asset = clip.source?.let { project.asset(it.mediaId) }
    val key = cutout.key
    var picking by remember { mutableStateOf(initialPicking) }
    ToolScaffold(
        header = {
            ScreenHeader(
                title = stringResource(R.string.effects_title_cutout),
                subtitle = stringResource(
                    if (key != null) {
                        R.string.effects_cutout_subtitle_chroma
                    } else {
                        R.string.effects_cutout_subtitle
                    },
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
                        modifier = Modifier.testTag("cutout-done"),
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
                SearchChip(
                    if (key != null) null else state.search,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
                val original = state.original
                if (key != null && original != null) {
                    OriginalInset(
                        original,
                        onClick = { picking = true },
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                    )
                }
                if (picking && original != null) {
                    PickOverlay(
                        original,
                        onPick = { x, y ->
                            picking = false
                            val color = actions.onPick(x, y)
                            if (color != null && key != null) {
                                actions.onCutout(cutout.copy(key = key.copy(color = color)))
                            }
                        },
                    )
                }
            }
        },
        controls = { fill ->
            SegmentedControl(
                segments = listOf(
                    Segment(stringResource(R.string.effects_cutout_mode_ai)),
                    Segment(stringResource(R.string.effects_cutout_mode_chroma)),
                ),
                selectedIndex = if (key != null) 1 else 0,
                onSelect = { actions.onChroma(it == 1) },
                style = SegmentedStyle.Tabs,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp)
                    .fillMaxWidth()
                    .testTag("cutout-mode"),
            )
            Column(
                modifier = fill
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(top = 16.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.effects_cutout_behind),
                    style = type.sectionLabel,
                    color = colors.textSecondary,
                )
                Row(
                    modifier = Modifier.selectableGroup(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    BEHIND.forEach { (behind, label) ->
                        BehindOption(
                            label = stringResource(label),
                            selected = cutout.behind == behind,
                            onClick = { actions.onCutout(cutout.copy(behind = behind)) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("behind:${behind.name}"),
                        )
                    }
                }
                if (cutout.behind == Behind.Color) {
                    Swatches(cutout.color) { actions.onCutout(cutout.copy(color = it)) }
                }
                if (key != null) {
                    KeyPanel(
                        cutout,
                        key,
                        onPicking = { picking = !picking },
                        canPick = state.original != null,
                        actions = actions,
                    )
                } else {
                    PersonPanel(cutout, actions)
                }
                TextAction(
                    text = stringResource(R.string.effects_cutout_remove),
                    onClick = actions.onRemove,
                    color = colors.textSecondary,
                    modifier = Modifier.testTag("cutout-remove"),
                )
            }
        },
        previewHeight = PREVIEW_HEIGHT,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Cutout"),
    )
}

/** Edge refine and Hair detail for the person cutout. */
@Composable
private fun PersonPanel(cutout: Cutout, actions: CutoutActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        LabelledSlider(
            label = stringResource(R.string.effects_cutout_edge),
            value = cutout.edgeRefine,
            onValueChange = { actions.onLive(cutout.copy(edgeRefine = it)) },
            onValueChangeFinished = { actions.onCutout(cutout) },
            valueText = (cutout.edgeRefine * 100).roundToInt().toString(),
            labelWidth = 104.dp,
            modifier = Modifier.testTag("cutout-edge"),
        )
        LabelledSlider(
            label = stringResource(R.string.effects_cutout_hair),
            value = cutout.hairDetail,
            onValueChange = { actions.onLive(cutout.copy(hairDetail = it)) },
            onValueChangeFinished = { actions.onCutout(cutout) },
            valueText = (cutout.hairDetail * 100).roundToInt().toString(),
            labelWidth = 104.dp,
            modifier = Modifier.testTag("cutout-hair"),
        )
        Text(
            text = stringResource(R.string.effects_cutout_note),
            style = ClipraTheme.typography.caption,
            color = ClipraTheme.colors.textTertiary,
        )
    }
}

/** The key colour with its eyedropper, then Similarity, Smoothness and Spill removal (#51). */
@Composable
private fun KeyPanel(
    cutout: Cutout,
    key: ChromaKey,
    onPicking: () -> Unit,
    canPick: Boolean,
    actions: CutoutActions,
) {
    val colors = ClipraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.effects_cutout_key_color),
                style = ClipraTheme.typography.label,
                color = colors.textPrimary,
                modifier = Modifier.width(104.dp),
            )
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(ClipraTheme.shapes.segment)
                    .background(Color(key.color.toInt()))
                    .border(1.dp, colors.borderStrong, ClipraTheme.shapes.segment),
            )
            Text(
                text = "#%06X".format(key.color and 0xFFFFFF),
                style = ClipraTheme.typography.meta,
                color = colors.textSecondary,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .weight(1f)
                    .testTag("key-hex"),
            )
            Surface(
                onClick = onPicking,
                enabled = canPick,
                modifier = Modifier
                    .size(ClipraDimens.TouchTarget)
                    .semantics { role = Role.Button }
                    .testTag("key-pick"),
                shape = ClipraTheme.shapes.button,
                color = colors.surfaceRaised,
                contentColor = if (canPick) colors.textPrimary else colors.textDisabled,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    ClipraIcon(
                        icon = ClipraIcons.Eyedropper,
                        contentDescription = stringResource(R.string.effects_cutout_pick),
                        size = 18.dp,
                    )
                }
            }
        }
        KEY_SLIDERS.forEach { (label, read, write) ->
            val value = read(key)
            LabelledSlider(
                label = stringResource(label),
                value = value,
                onValueChange = { actions.onLive(cutout.copy(key = write(key, it))) },
                onValueChangeFinished = { actions.onCutout(cutout) },
                valueText = (value * 100).roundToInt().toString(),
                labelWidth = 104.dp,
                modifier = Modifier.testTag("key-$label"),
            )
        }
    }
}

/** The clip's own picture, small in a corner of the preview; tap it to pick the key colour. */
@Composable
private fun OriginalInset(picture: Bitmap, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val label = stringResource(R.string.effects_cutout_original)
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = label,
            style = ClipraTheme.typography.toolLabel,
            color = colors.textPrimary,
            modifier = Modifier
                .clip(ClipraTheme.shapes.thumb)
                .background(colors.background.copy(alpha = 0.8f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        )
        Image(
            bitmap = picture.asImageBitmap(),
            contentDescription = stringResource(R.string.effects_cutout_pick),
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .width(48.dp)
                .aspectRatio(picture.width.toFloat() / picture.height)
                .clip(ClipraTheme.shapes.thumb)
                .border(2.dp, colors.textPrimary, ClipraTheme.shapes.thumb)
                .clickable(role = Role.Button, onClick = onClick)
                .testTag("cutout-original"),
        )
    }
}

/** The clip's own picture over the preview while picking: tap the colour to key out. */
@Composable
private fun PickOverlay(picture: Bitmap, onPick: (Float, Float) -> Unit) {
    val colors = ClipraTheme.colors
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.ad.background)
            .testTag("cutout-picking"),
    ) {
        Image(
            bitmap = picture.asImageBitmap(),
            contentDescription = stringResource(R.string.effects_cutout_pick_hint),
            contentScale = ContentScale.FillBounds,
            modifier = Modifier
                .align(Alignment.Center)
                .aspectRatio(picture.width.toFloat() / picture.height)
                .pointerInput(Unit) {
                    detectTapGestures { at ->
                        onPick(at.x / size.width, at.y / size.height)
                    }
                },
        )
        Text(
            text = stringResource(R.string.effects_cutout_pick_hint),
            style = ClipraTheme.typography.meta,
            color = colors.accent,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(8.dp)
                .clip(ClipraTheme.shapes.thumb)
                .background(colors.background.copy(alpha = 0.8f))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** "Person found" over the preview, how far the search is, or "Keyed" (null) for a chroma key. */
@Composable
private fun SearchChip(search: PersonSearch?, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val text = when (search) {
        null -> stringResource(R.string.effects_cutout_keyed)

        is PersonSearch.Finding -> stringResource(
            R.string.effects_cutout_finding,
            (search.progress * 100).roundToInt(),
        )

        PersonSearch.Found -> stringResource(R.string.effects_cutout_found)

        PersonSearch.NotFound -> stringResource(R.string.effects_cutout_not_found)
    }
    Text(
        text = text,
        style = ClipraTheme.typography.meta,
        color = if (search == PersonSearch.NotFound) colors.textSecondary else colors.accent,
        modifier = modifier
            .clip(ClipraTheme.shapes.thumb)
            .background(colors.background.copy(alpha = 0.8f))
            .padding(horizontal = 8.dp, vertical = 3.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("cutout-search"),
    )
}

@Composable
private fun BehindOption(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Surface(
        modifier = modifier
            .height(44.dp)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
        shape = ClipraTheme.shapes.button,
        color = if (selected) colors.accentSelectedBackground else colors.surface,
        contentColor = if (selected) colors.accent else colors.textPrimary,
        border = BorderStroke(
            1.5.dp,
            if (selected) colors.accent else colors.border,
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = label,
                style = ClipraTheme.typography.chip,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

/** The colours that can go behind the person. */
@Composable
private fun Swatches(selected: Long, onPick: (Long) -> Unit) {
    val colors = ClipraTheme.colors
    Row(
        modifier = Modifier.selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SWATCHES.forEach { (value, name) ->
            val label = stringResource(name)
            val on = value == selected
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(Color(value.toInt()))
                    .border(
                        width = if (on) 3.dp else 1.dp,
                        color = if (on) colors.accent else colors.border,
                        shape = CircleShape,
                    )
                    .selectable(selected = on, role = Role.RadioButton) { onPick(value) }
                    .semantics { contentDescription = label }
                    .testTag("swatch:$value"),
            )
        }
    }
}

private val BEHIND = listOf(
    Behind.LayerBelow to R.string.effects_cutout_layer_below,
    Behind.Blur to R.string.effects_cutout_blur,
    Behind.Clear to R.string.effects_cutout_clear,
    Behind.Color to R.string.effects_cutout_color,
)

private val SWATCHES = listOf(
    TextColors.GREEN to R.string.effects_cutout_green,
    TextColors.BLUE to R.string.effects_cutout_blue,
    TextColors.BLACK to R.string.effects_cutout_black,
    TextColors.WHITE to R.string.effects_cutout_white,
    TextColors.YELLOW to R.string.effects_cutout_yellow,
    TextColors.RED to R.string.effects_cutout_red,
)

/** The chroma key's sliders: label, reading and writing its value. */
private val KEY_SLIDERS: List<Triple<Int, (ChromaKey) -> Float, (ChromaKey, Float) -> ChromaKey>> =
    listOf(
        Triple(
            R.string.effects_cutout_similarity,
            { it.similarity },
            { k, v -> k.copy(similarity = v) },
        ),
        Triple(
            R.string.effects_cutout_smoothness,
            { it.smoothness },
            { k, v -> k.copy(smoothness = v) },
        ),
        Triple(R.string.effects_cutout_spill, { it.spill }, { k, v -> k.copy(spill = v) }),
    )

private val PREVIEW_HEIGHT = 330.dp
