package tech.idct.clipra.feature.editor.stabilize

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.model.CameraStep
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Stabilization
import tech.idct.clipra.core.model.Stabilizing
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.layers.DoneButton
import tech.idct.clipra.feature.editor.layers.PREVIEW_HEIGHT
import tech.idct.clipra.feature.editor.layers.displayName
import tech.idct.clipra.feature.editor.layers.typeName
import tech.idct.clipra.feature.editor.timeline.formatTimecode
import kotlin.math.roundToInt

/** How far the analysis of a stabilized clip's camera motion (#54) has got. */
sealed interface StabilizeAnalysis {
    /** Asked for, and not started yet. */
    data object Waiting : StabilizeAnalysis

    /** Under way, [progress] from 0 to 1. */
    data class Working(val progress: Float) : StabilizeAnalysis

    /** The camera's [steps] over the part of the media the clip plays, for a picture [aspect]. */
    data class Ready(val steps: List<CameraStep>, val aspect: Float) : StabilizeAnalysis

    /** The clip's media couldn't be read. */
    data object Failed : StabilizeAnalysis
}

/** What the Stabilize sheet can change. */
class StabilizeActions(
    val onDone: () -> Unit,
    val onStabilize: (Stabilization?) -> Unit,
    val onCancel: () -> Unit,
    val onRetry: () -> Unit,
)

/**
 * The Stabilize sheet (#54): stabilization on or off, its strength (smoothness against crop)
 * and the crop it takes, worked out as the slider moves. While the camera's motion is analysed
 * on the device, the sheet shows how far it is and the clip plays as it was shot.
 */
@Composable
fun StabilizeScreen(
    project: Project,
    clip: Clip,
    analysis: StabilizeAnalysis?,
    actions: StabilizeActions,
    preview: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val settings = clip.stabilize
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Stabilize"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.editor_stabilize_header, displayName(project, clip)),
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
            SwitchRow(
                title = stringResource(R.string.editor_stabilize_switch),
                subtitle = stringResource(R.string.editor_stabilize_switch_detail),
                checked = settings != null,
                onCheckedChange = { actions.onStabilize(if (it) Stabilization() else null) },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stabilize-switch"),
            )
            val committed = settings?.strength ?: Stabilization().strength
            var strength by remember(committed) { mutableIntStateOf(committed) }
            LabelledSlider(
                label = stringResource(R.string.editor_stabilize_strength),
                value = strength.toFloat(),
                onValueChange = { strength = it.roundToInt() },
                onValueChangeFinished = { actions.onStabilize(Stabilization(strength)) },
                valueText = strength.toString(),
                valueRange = 0f..100f,
                enabled = settings != null,
                labelWidth = 72.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("stabilize-strength"),
            )
            when (analysis) {
                null -> Unit

                is StabilizeAnalysis.Ready -> {
                    val crop = remember(analysis, strength) {
                        Stabilizing.stabilize(analysis.steps, strength, analysis.aspect).crop
                    }
                    CropReadout(crop)
                }

                StabilizeAnalysis.Waiting, is StabilizeAnalysis.Working -> {
                    val progress = (analysis as? StabilizeAnalysis.Working)?.progress ?: 0f
                    Status(
                        text = stringResource(
                            R.string.editor_stabilize_analysing,
                            (progress * 100).roundToInt(),
                        ),
                    )
                    ProgressBar(progress)
                    SecondaryButton(
                        text = stringResource(R.string.editor_stabilize_cancel),
                        onClick = actions.onCancel,
                        modifier = Modifier.testTag("stabilize-cancel"),
                    )
                }

                StabilizeAnalysis.Failed -> {
                    Status(text = stringResource(R.string.editor_stabilize_failed))
                    SecondaryButton(
                        text = stringResource(R.string.editor_stabilize_retry),
                        onClick = actions.onRetry,
                        modifier = Modifier.testTag("stabilize-retry"),
                    )
                }
            }
            Text(
                text = stringResource(R.string.editor_stabilize_detail),
                style = type.caption,
                color = colors.textSecondary,
            )
        }
    }
}

/** The share of the picture the zoom crops away, so no edge shows. */
@Composable
private fun CropReadout(crop: Float) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {}
            .testTag("stabilize-crop"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.editor_stabilize_crop),
            style = type.body,
            color = colors.textPrimary,
            modifier = Modifier.weight(1f),
        )
        Text(
            text = stringResource(R.string.editor_stabilize_crop_value, (crop * 100).roundToInt()),
            style = type.metaStrong,
            color = colors.accent,
        )
    }
}

@Composable
private fun Status(text: String) {
    Text(
        text = text,
        style = ClipraTheme.typography.body,
        color = ClipraTheme.colors.textPrimary,
        modifier = Modifier
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag("stabilize-status"),
    )
}

@Composable
private fun ProgressBar(progress: Float) {
    val colors = ClipraTheme.colors
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(CircleShape)
            .background(colors.surfaceRaised),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(CircleShape)
                .background(colors.accent),
        )
    }
}
