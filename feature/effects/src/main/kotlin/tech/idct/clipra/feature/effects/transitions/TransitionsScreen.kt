package tech.idct.clipra.feature.effects.transitions

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transitions
import tech.idct.clipra.feature.effects.R
import tech.idct.clipra.feature.effects.common.PICKER_COLUMNS
import tech.idct.clipra.feature.effects.common.PickerCategory
import tech.idct.clipra.feature.effects.common.PickerPreview
import tech.idct.clipra.feature.effects.common.PickerTile
import java.util.Locale
import kotlin.math.roundToLong

/** What the Transitions screen can do. */
class TransitionsActions(
    val onBack: () -> Unit,
    val onCategory: (TransitionCategory) -> Unit,
    val onPick: (type: String?) -> Unit,
    val onDuration: (Long) -> Unit,
    val onDurationDone: () -> Unit,
    val onEasing: (Easing) -> Unit,
    val onPlay: () -> Unit,
    val onApplyToAll: () -> Unit,
)

/**
 * The Transitions board (#26): the cut's midpoint in the preview with the transition's name and
 * duration and a play button, the categories, the 24 transitions as live thumbnails of the two
 * clips, the duration and the easing. [durationUs] follows the slider while it moves.
 */
@Composable
fun TransitionsScreen(
    state: TransitionsUiState,
    durationUs: Long,
    actions: TransitionsActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val cut = state.cut ?: return
    val selected = state.transition?.type
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Transitions"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.effects_transition_title),
            subtitle = stringResource(
                R.string.effects_transition_subtitle,
                name(project, cut.from),
                name(project, cut.to),
            ),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
            actions = {
                TextAction(
                    text = stringResource(R.string.effects_apply_to_all),
                    onClick = actions.onApplyToAll,
                    modifier = Modifier.testTag("apply-to-all"),
                )
                PrimaryButton(
                    text = stringResource(R.string.effects_done),
                    onClick = actions.onBack,
                    compact = true,
                    modifier = Modifier.testTag("transitions-done"),
                )
            },
        )
        PickerPreview(project.format.ratio.let { it.width.toFloat() / it.height }, preview) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = transitionName(selected),
                    style = ClipraTheme.typography.button,
                    color = colors.textPrimary,
                )
                if (selected != null) {
                    val length = seconds(durationUs)
                    Text(
                        text = stringResource(R.string.effects_transition_meta, length),
                        style = ClipraTheme.typography.meta,
                        color = colors.textSecondary,
                    )
                }
            }
            if (selected != null) {
                PlayButton(
                    onClick = actions.onPlay,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 12.dp, bottom = 8.dp),
                )
            }
        }
        Row(
            modifier = Modifier
                .padding(start = 16.dp, end = 16.dp, top = 10.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TransitionCategory.entries.forEach { entry ->
                val (label, count) = when (entry) {
                    TransitionCategory.All -> R.string.effects_category_all to Transitions.all.size

                    TransitionCategory.Basic ->
                        R.string.effects_category_basic to Transitions.basic.size

                    TransitionCategory.Motion ->
                        R.string.effects_category_motion to Transitions.motion.size

                    TransitionCategory.Stylized ->
                        R.string.effects_category_stylized to Transitions.stylized.size
                }
                PickerCategory(
                    label = stringResource(label),
                    count = count,
                    selected = entry == state.category,
                    onClick = { actions.onCategory(entry) },
                    modifier = Modifier.testTag("category:${entry.name}"),
                )
            }
        }
        val shown = listOf<String?>(null) + when (state.category) {
            TransitionCategory.All -> Transitions.all
            TransitionCategory.Basic -> Transitions.basic
            TransitionCategory.Motion -> Transitions.motion
            TransitionCategory.Stylized -> Transitions.stylized
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            shown.chunked(PICKER_COLUMNS).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { type ->
                        PickerTile(
                            name = transitionName(type),
                            thumbnail = state.thumbnails[type.orEmpty()],
                            selected = type == selected,
                            onClick = { actions.onPick(type) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("transition:${type ?: "none"}"),
                        )
                    }
                    repeat(PICKER_COLUMNS - row.size) { Spacer(modifier = Modifier.weight(1f)) }
                }
            }
        }
        val longest = minOf(Transitions.MAX_DURATION_US, cut.maxTransitionUs)
        val shortestSeconds = Transitions.MIN_DURATION_US / 1_000_000f
        val longestSeconds = Transitions.MAX_DURATION_US / 1_000_000f
        LabelledSlider(
            label = stringResource(R.string.effects_duration),
            value = durationUs / 1_000_000f,
            onValueChange = { seconds ->
                val tenths = (seconds * 10).roundToLong().coerceIn(1, longest / 100_000)
                actions.onDuration(tenths * 100_000)
            },
            onValueChangeFinished = actions.onDurationDone,
            valueText = stringResource(R.string.effects_seconds, seconds(durationUs)),
            valueRange = shortestSeconds..longestSeconds,
            enabled = selected != null,
            labelWidth = 72.dp,
            modifier = Modifier
                .padding(horizontal = 16.dp)
                .height(52.dp)
                .testTag("transition-duration"),
        )
        SegmentedControl(
            segments = listOf(
                Segment(stringResource(R.string.effects_easing_linear), enabled = selected != null),
                Segment(stringResource(R.string.effects_easing_in_out), enabled = selected != null),
                Segment(stringResource(R.string.effects_easing_out), enabled = selected != null),
            ),
            selectedIndex = (state.transition?.easing ?: Easing.EaseInOut).ordinal,
            onSelect = { actions.onEasing(Easing.entries[it]) },
            style = SegmentedStyle.Tabs,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 12.dp)
                .testTag("transition-easing"),
        )
    }
}

@Composable
private fun PlayButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .size(ClipraDimens.TouchTarget)
            .semantics { role = Role.Button }
            .testTag("transition-play"),
        shape = CircleShape,
        color = colors.surfaceRaised,
        contentColor = colors.textPrimary,
    ) {
        androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
            ClipraIcon(
                icon = ClipraIcons.Play,
                contentDescription = stringResource(R.string.effects_transition_play),
                size = 20.dp,
            )
        }
    }
}

/** A clip's file name, or its kind when it has none. */
@Composable
private fun name(project: Project, clip: Clip): String =
    clip.source?.let { project.asset(it.mediaId) }?.displayName?.takeIf { it.isNotEmpty() }
        ?: clip.text?.text.orEmpty()

/** "0.6": tenths of a second. */
private fun seconds(us: Long): String = String.format(Locale.ROOT, "%.1f", us / 1_000_000.0)

/** A transition's name; null is None. */
@Composable
fun transitionName(type: String?): String = stringResource(
    when (type) {
        "dissolve" -> R.string.effects_transition_dissolve
        "fade-black" -> R.string.effects_transition_fade_black
        "fade-white" -> R.string.effects_transition_fade_white
        "wipe-left" -> R.string.effects_transition_wipe_left
        "wipe-down" -> R.string.effects_transition_wipe_down
        "clock" -> R.string.effects_transition_clock
        "iris" -> R.string.effects_transition_iris
        "split" -> R.string.effects_transition_split
        "slide-left" -> R.string.effects_transition_slide_left
        "slide-up" -> R.string.effects_transition_slide_up
        "push" -> R.string.effects_transition_push
        "zoom-in" -> R.string.effects_transition_zoom_in
        "zoom-out" -> R.string.effects_transition_zoom_out
        "whip-pan" -> R.string.effects_transition_whip_pan
        "spin" -> R.string.effects_transition_spin
        "shake" -> R.string.effects_transition_shake
        "cube" -> R.string.effects_transition_cube
        "glitch" -> R.string.effects_transition_glitch
        "flash" -> R.string.effects_transition_flash
        "blur" -> R.string.effects_transition_blur
        "pixelate" -> R.string.effects_transition_pixelate
        "light-leak" -> R.string.effects_transition_light_leak
        "film-burn" -> R.string.effects_transition_film_burn
        "ink" -> R.string.effects_transition_ink
        else -> R.string.effects_filter_none
    },
)
