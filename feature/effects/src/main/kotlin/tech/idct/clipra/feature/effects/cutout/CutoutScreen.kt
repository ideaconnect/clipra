package tech.idct.clipra.feature.effects.cutout

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.model.Behind
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
)

/**
 * The Cutout board (#50): the preview with how the search for people stands, what shows behind
 * them (the layer below, a blur of the clip, nothing, or a colour), Edge refine and Hair detail.
 * Chroma key joins with #51.
 */
@Composable
fun CutoutScreen(
    state: CutoutUiState,
    cutout: Cutout,
    actions: CutoutActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val project = state.project ?: return
    val clip = state.clip ?: return
    val (layer, _) = project.findClip(clip.id) ?: return
    val asset = clip.source?.let { project.asset(it.mediaId) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Cutout"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.effects_title_cutout),
            subtitle = stringResource(
                R.string.effects_cutout_subtitle,
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
                SearchChip(
                    state.search,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp),
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
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
                style = type.caption,
                color = colors.textTertiary,
            )
            TextAction(
                text = stringResource(R.string.effects_cutout_remove),
                onClick = actions.onRemove,
                color = colors.textSecondary,
                modifier = Modifier.testTag("cutout-remove"),
            )
        }
    }
}

/** "Person found" over the preview, or how far the search is. */
@Composable
private fun SearchChip(search: PersonSearch, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val text = when (search) {
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

private val PREVIEW_HEIGHT = 330.dp
