package tech.idct.clipra.feature.effects.filters

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
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
import tech.idct.clipra.core.designsystem.component.TextAction
import tech.idct.clipra.core.designsystem.component.ToolScaffold
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.filter
import tech.idct.clipra.feature.effects.R
import tech.idct.clipra.feature.effects.common.PICKER_COLUMNS
import tech.idct.clipra.feature.effects.common.PickerCategory
import tech.idct.clipra.feature.effects.common.PickerTile
import tech.idct.clipra.feature.effects.common.clipLabel
import kotlin.math.roundToInt

/** What the Filters screen can do. */
class FiltersActions(
    val onBack: () -> Unit,
    val onCategory: (FilterCategory) -> Unit,
    val onPick: (type: String?) -> Unit,
    val onStrength: (Float) -> Unit,
    val onStrengthDone: () -> Unit,
    val onCompare: (holding: Boolean) -> Unit,
    val onApplyToAll: () -> Unit,
)

/**
 * The Filters board (#25): the clip in the preview with the filter's name and strength and a
 * hold-to-compare button, the categories, the 24 filters as live thumbnails of the current
 * frame, and the strength slider. [strength] follows the slider while it moves.
 */
@Composable
fun FiltersScreen(
    state: FiltersUiState,
    strength: Float,
    actions: FiltersActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val selected = clip.filter?.type
    val assetName = clipLabel(project, clip)
    ToolScaffold(
        header = {
            ScreenHeader(
                title = stringResource(R.string.effects_title_filters),
                subtitle = pluralStringResource(
                    R.plurals.effects_filters_subtitle,
                    Filters.all.size,
                    assetName,
                    Filters.all.size,
                ),
                navigation = HeaderNavigation.Back,
                onNavigate = actions.onBack,
                actions = {
                    PrimaryButton(
                        text = stringResource(R.string.effects_done),
                        onClick = actions.onBack,
                        compact = true,
                        modifier = Modifier.testTag("filters-done"),
                    )
                },
            )
        },
        preview = {
            Box(
                modifier = Modifier
                    .aspectRatio(
                        project.format.ratio.let {
                            it.width.toFloat() / it.height
                        },
                        matchHeightConstraintsFirst = true,
                    )
                    .testTag("preview"),
            ) {
                preview()
            }
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = filterName(selected),
                    style = ClipraTheme.typography.button,
                    color = colors.textPrimary,
                )
                if (selected != null) {
                    Text(
                        text = stringResource(
                            R.string.effects_filter_strength,
                            (strength * 100).roundToInt(),
                        ),
                        style = ClipraTheme.typography.meta,
                        color = colors.textSecondary,
                    )
                }
            }
            CompareButton(
                onCompare = actions.onCompare,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 12.dp, bottom = 8.dp),
            )
        },
        controls = { fill ->
            Categories(state.category, actions)
            Grid(
                category = state.category,
                selected = selected,
                thumbnails = state.thumbnails,
                onPick = actions.onPick,
                modifier = fill
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            )
        },
        previewHeight = 240.dp,
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Filters"),
        bottom = {
            LabelledSlider(
                label = stringResource(R.string.effects_strength),
                value = strength,
                onValueChange = actions.onStrength,
                onValueChangeFinished = actions.onStrengthDone,
                valueText = (strength * 100).roundToInt().toString(),
                enabled = selected != null,
                labelWidth = 64.dp,
                modifier = Modifier
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                    .height(52.dp)
                    .testTag("filter-strength"),
            )
        },
    )
}

/** Shows the original while held. */
@Composable
private fun CompareButton(onCompare: (Boolean) -> Unit, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    val description = stringResource(R.string.effects_compare)
    Box(
        modifier = modifier
            .size(ClipraDimens.TouchTarget)
            .clip(CircleShape)
            .background(colors.surfaceRaised)
            .semantics {
                contentDescription = description
                role = Role.Button
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onPress = {
                        onCompare(true)
                        tryAwaitRelease()
                        onCompare(false)
                    },
                )
            }
            .testTag("filter-compare"),
        contentAlignment = Alignment.Center,
    ) {
        ClipraIcon(
            icon = ClipraIcons.FilterCompare,
            contentDescription = null,
            tint = colors.textPrimary,
            size = 20.dp,
        )
    }
}

@Composable
private fun Categories(category: FilterCategory, actions: FiltersActions) {
    Row(
        modifier = Modifier.padding(start = 16.dp, end = 12.dp, top = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .weight(1f)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterCategory.entries.forEach { entry ->
                val (label, count) = when (entry) {
                    FilterCategory.All -> R.string.effects_category_all to Filters.all.size

                    FilterCategory.Looks -> R.string.effects_category_looks to Filters.looks.size

                    FilterCategory.Stylized ->
                        R.string.effects_category_stylized to Filters.stylized.size
                }
                PickerCategory(
                    label = stringResource(label),
                    count = count,
                    selected = entry == category,
                    onClick = { actions.onCategory(entry) },
                    modifier = Modifier.testTag("category:${entry.name}"),
                )
            }
        }
        Spacer(modifier = Modifier.size(8.dp))
        TextAction(
            text = stringResource(R.string.effects_apply_to_all),
            onClick = actions.onApplyToAll,
            modifier = Modifier.testTag("apply-to-all"),
        )
    }
}

@Composable
private fun Grid(
    category: FilterCategory,
    selected: String?,
    thumbnails: Map<String, Bitmap>,
    onPick: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val shown = listOf<String?>(null) + when (category) {
        FilterCategory.All -> Filters.all
        FilterCategory.Looks -> Filters.looks
        FilterCategory.Stylized -> Filters.stylized
    }
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        shown.chunked(PICKER_COLUMNS).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { type ->
                    PickerTile(
                        name = filterName(type),
                        thumbnail = thumbnails[type.orEmpty()],
                        selected = type == selected,
                        onClick = { onPick(type) },
                        modifier = Modifier
                            .weight(1f)
                            .testTag("filter:${type ?: "none"}"),
                    )
                }
                repeat(PICKER_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
        }
    }
}

/** A filter's name; null is None. */
@Composable
fun filterName(type: String?): String = stringResource(
    when (type) {
        null -> R.string.effects_filter_none
        "vivid" -> R.string.effects_filter_vivid
        "warm" -> R.string.effects_filter_warm
        "cool" -> R.string.effects_filter_cool
        "cinematic" -> R.string.effects_filter_cinematic
        "matte" -> R.string.effects_filter_matte
        "mono" -> R.string.effects_filter_mono
        "noir" -> R.string.effects_filter_noir
        "sepia" -> R.string.effects_filter_sepia
        "vintage" -> R.string.effects_filter_vintage
        "film" -> R.string.effects_filter_film
        "night" -> R.string.effects_filter_night
        "neon" -> R.string.effects_filter_neon
        "pastel" -> R.string.effects_filter_pastel
        "mosaic" -> R.string.effects_filter_mosaic
        "blur" -> R.string.effects_filter_blur
        "glitch" -> R.string.effects_filter_glitch
        "vhs" -> R.string.effects_filter_vhs
        "grain" -> R.string.effects_filter_grain
        "vignette" -> R.string.effects_filter_vignette
        "rgb-split" -> R.string.effects_filter_rgb_split
        "light-leak" -> R.string.effects_filter_light_leak
        "halftone" -> R.string.effects_filter_halftone
        "duotone" -> R.string.effects_filter_duotone
        "soft-glow" -> R.string.effects_filter_soft_glow
        else -> R.string.effects_filter_none
    },
)
