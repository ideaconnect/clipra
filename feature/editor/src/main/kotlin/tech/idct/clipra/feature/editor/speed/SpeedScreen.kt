package tech.idct.clipra.feature.editor.speed

import androidx.compose.foundation.background
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.SpeedCurve
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.layers.DoneButton
import tech.idct.clipra.feature.editor.layers.Option
import tech.idct.clipra.feature.editor.layers.PREVIEW_HEIGHT
import tech.idct.clipra.feature.editor.layers.displayName
import tech.idct.clipra.feature.editor.layers.typeName
import tech.idct.clipra.feature.editor.timeline.formatTimecode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols

/** What the Speed board can change. */
class SpeedActions(val onDone: () -> Unit, val onSpeed: (Speed) -> Unit)

/**
 * The Speed board (#22, the tablet's Speed tab): a constant speed, a speed ramp (Custom with
 * its own three speeds), and reverse. The clip's length follows, and later clips move with it.
 */
@Composable
fun SpeedScreen(
    project: Project,
    clip: Clip,
    actions: SpeedActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val speed = clip.speed
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Speed"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.editor_speed_header, displayName(project, clip)),
            subtitle = stringResource(
                R.string.editor_layers_subtitle,
                typeName(clip.type),
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
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 14.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.editor_speed_constant),
                style = type.sectionLabel,
                color = colors.textSecondary,
            )
            val constant = SpeedCurve.constants.indexOf(speed.rate).takeIf { speed.ramp == null }
            SegmentedControl(
                segments = SpeedCurve.constants.map { Segment(speedLabel(it)) },
                selectedIndex = constant ?: -1,
                onSelect = {
                    actions.onSpeed(Speed(rate = SpeedCurve.constants[it], reverse = speed.reverse))
                },
                monospace = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("speed-constant"),
            )
            Text(
                text = stringResource(R.string.editor_speed_ramp),
                style = type.sectionLabel,
                color = colors.textSecondary,
                modifier = Modifier.padding(top = 4.dp),
            )
            val ramps = SpeedCurve.ramps.keys.toList() + SpeedCurve.CUSTOM
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ramps.chunked(3).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { ramp ->
                            Option(
                                label = rampLabel(ramp),
                                selected = speed.ramp == ramp,
                                onClick = {
                                    actions.onSpeed(Speed(ramp = ramp, reverse = speed.reverse))
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .testTag("ramp:$ramp"),
                                raised = true,
                            )
                        }
                    }
                }
            }
            if (speed.ramp == SpeedCurve.CUSTOM) CustomRamp(speed, actions)
            SwitchRow(
                title = stringResource(R.string.editor_speed_reverse),
                subtitle = stringResource(R.string.editor_speed_reverse_detail),
                checked = speed.reverse,
                onCheckedChange = { actions.onSpeed(speed.copy(reverse = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("speed-reverse"),
            )
            Text(
                text = stringResource(R.string.editor_speed_pitch),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
    }
}

/** A Custom ramp's three speeds (start, middle, end), each one undo step when let go. */
@Composable
private fun CustomRamp(speed: Speed, actions: SpeedActions) {
    val committed = speed.custom ?: SpeedCurve.customDefault
    var live by remember(committed) { mutableStateOf(committed) }
    val labels = listOf(
        R.string.editor_speed_custom_start,
        R.string.editor_speed_custom_middle,
        R.string.editor_speed_custom_end,
    )
    Column(modifier = Modifier.testTag("speed-custom")) {
        labels.forEachIndexed { i, label ->
            LabelledSlider(
                label = stringResource(label),
                value = live[i],
                onValueChange = { value ->
                    live = live.toMutableList().also { it[i] = value }
                },
                onValueChangeFinished = { actions.onSpeed(speed.copy(custom = live)) },
                valueText = speedLabel(live[i].toDouble()),
                valueRange = SpeedCurve.MIN_SPEED..SpeedCurve.MAX_SPEED,
                labelWidth = 72.dp,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("speed-custom:$i"),
            )
        }
    }
}

/** "0.25×", "2×", with the language's decimal separator. */
@Composable
internal fun speedLabel(rate: Double): String {
    val symbols = DecimalFormatSymbols.getInstance(LocalConfiguration.current.locales[0])
    return stringResource(R.string.editor_speed_value, DecimalFormat("0.##", symbols).format(rate))
}

@Composable
private fun rampLabel(ramp: String): String = stringResource(
    when (ramp) {
        "montage" -> R.string.editor_speed_ramp_montage
        "hero" -> R.string.editor_speed_ramp_hero
        "bullet" -> R.string.editor_speed_ramp_bullet
        "flash_in" -> R.string.editor_speed_ramp_flash_in
        "flash_out" -> R.string.editor_speed_ramp_flash_out
        else -> R.string.editor_speed_ramp_custom
    },
)
