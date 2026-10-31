package tech.idct.clipra.feature.effects.reframe

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Reframe
import tech.idct.clipra.feature.effects.R
import kotlin.math.roundToInt

/** What the Auto-reframe board can do. */
class ReframeActions(
    val onBack: () -> Unit,
    val onRatio: (AspectRatio) -> Unit,
    val onFollow: (Reframe.Follow) -> Unit,
    val onMotion: (Int) -> Unit,
    val onKeepInside: (Boolean) -> Unit,
    val onCreate: () -> Unit,
    val onCancel: () -> Unit,
)

/**
 * Auto-reframe (#53, Reframe board): the project's frame at the playhead with the new format's
 * window on it, the result, the new format, what the crop follows, how quickly it moves, and
 * whether titles stay inside. Create makes the copy; while every shot is tracked, a progress bar
 * stands in for the button.
 */
@Composable
fun ReframeScreen(
    state: ReframeUiState,
    actions: ReframeActions,
    playheadUs: Long,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val from = project.format.ratio.label
    val to = state.ratio.label
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Reframe"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.effects_title_reframe),
            subtitle = stringResource(R.string.reframe_subtitle, project.name, from, to),
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val source = stringResource(R.string.reframe_source, from, timecode(playheadUs))
            SourcePreview(state, label = source)
            ResultRow(state)
            Section(stringResource(R.string.reframe_new_format)) {
                SegmentedControl(
                    segments = state.ratios.map { Segment(it.label) },
                    selectedIndex = state.ratios.indexOf(state.ratio),
                    onSelect = { actions.onRatio(state.ratios[it]) },
                    style = SegmentedStyle.Tabs,
                    monospace = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reframe-ratio"),
                )
            }
            Section(stringResource(R.string.reframe_follow)) {
                SegmentedControl(
                    segments = Reframe.Follow.entries.map {
                        Segment(stringResource(it.labelRes()))
                    },
                    selectedIndex = state.follow.ordinal,
                    onSelect = { actions.onFollow(Reframe.Follow.entries[it]) },
                    style = SegmentedStyle.Tabs,
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("reframe-follow"),
                )
            }
            CameraMotion(state.motion, actions.onMotion)
            SwitchRow(
                title = stringResource(R.string.reframe_keep_inside),
                checked = state.keepInside,
                onCheckedChange = actions.onKeepInside,
                modifier = Modifier.testTag("reframe-keep-inside"),
            )
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 16.dp),
        ) {
            val creating = state.creating
            if (creating == null) {
                PrimaryButton(
                    text = stringResource(R.string.reframe_create, to),
                    onClick = actions.onCreate,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .testTag("reframe-create"),
                )
            } else {
                Creating(creating, actions.onCancel)
            }
        }
    }
}

/** The project's frame with the new format's window, the outside dimmed. */
@Composable
private fun SourcePreview(state: ReframeUiState, label: String) {
    val colors = ClipraTheme.colors
    val ratio = state.project?.format?.ratio ?: return
    val window = state.window
    val description = stringResource(R.string.reframe_source_description, state.ratio.label)
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = Alignment.Center,
    ) {
        // As wide as the screen, but never taller than the board's 16:9 frame plus half.
        val aspect = ratio.width.toFloat() / ratio.height
        val height = minOf(maxWidth.value / aspect, MAX_SOURCE_HEIGHT)
        val width = height * aspect
        Box(
            modifier = Modifier
                .size(width.dp, height.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .semantics { contentDescription = description }
                .testTag("reframe-source"),
        ) {
            state.source?.let { Picture(it) }
            if (window != null) {
                val dim = colors.background.copy(alpha = DIM)
                val accent = colors.accent
                val faceColor = colors.onDeviceText
                val face = state.face
                Canvas(Modifier.fillMaxSize()) {
                    val topLeft = Offset(window.left * size.width, window.top * size.height)
                    val windowSize = Size(
                        (window.right - window.left) * size.width,
                        (window.bottom - window.top) * size.height,
                    )
                    val hole = Path().apply {
                        addRect(Rect(topLeft, windowSize))
                    }
                    clipPath(hole, ClipOp.Difference) { drawRect(dim) }
                    if (face != null) {
                        drawRoundRect(
                            color = faceColor,
                            topLeft = Offset(face.left * size.width, face.top * size.height),
                            size = Size(
                                (face.right - face.left) * size.width,
                                (face.bottom - face.top) * size.height,
                            ),
                            cornerRadius = CornerRadius(6.dp.toPx()),
                            style = Stroke(1.5.dp.toPx()),
                        )
                    }
                    val stroke = 2.dp.toPx()
                    drawRect(
                        color = accent,
                        topLeft = topLeft + Offset(stroke / 2, stroke / 2),
                        size = Size(windowSize.width - stroke, windowSize.height - stroke),
                        style = Stroke(stroke),
                    )
                }
            }
            Text(
                text = label,
                style = ClipraTheme.typography.meta,
                color = colors.textPrimary,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.background.copy(alpha = LABEL))
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/** The new frame at the playhead, and what the crop does. */
@Composable
private fun ResultRow(state: ReframeUiState) {
    val colors = ClipraTheme.colors
    val ratio = state.ratio
    val description = stringResource(R.string.reframe_result_description, ratio.label)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size((RESULT_HEIGHT * ratio.width / ratio.height).dp, RESULT_HEIGHT.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .border(1.dp, colors.border, RoundedCornerShape(6.dp))
                .semantics { contentDescription = description }
                .testTag("reframe-result"),
        ) {
            state.result?.let { Picture(it) }
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(
                text = stringResource(R.string.reframe_result, ratio.label),
                style = ClipraTheme.typography.sectionLabel,
                color = colors.textPrimary,
            )
            Text(
                text = stringResource(
                    when (state.follow) {
                        Reframe.Follow.Subject -> R.string.reframe_result_subject
                        Reframe.Follow.Face -> R.string.reframe_result_face
                        Reframe.Follow.Fixed -> R.string.reframe_result_fixed
                    },
                ),
                style = ClipraTheme.typography.caption,
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun Picture(bitmap: Bitmap) {
    Image(
        bitmap = bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxSize(),
    )
}

/** Camera motion, Smooth to Quick, as the board's slider. */
@Composable
private fun CameraMotion(motion: Int, onMotion: (Int) -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.heightIn(min = 48.dp),
    ) {
        Text(
            text = stringResource(R.string.reframe_camera_motion),
            style = type.body,
            color = colors.textPrimary,
            modifier = Modifier.width(110.dp),
        )
        Text(
            text = stringResource(R.string.reframe_smooth),
            style = type.caption,
            color = colors.textSecondary,
        )
        Slider(
            value = motion.toFloat(),
            onValueChange = { onMotion(it.roundToInt()) },
            valueRange = 0f..100f,
            colors = SliderDefaults.colors(
                thumbColor = colors.accent,
                activeTrackColor = colors.accent,
                inactiveTrackColor = colors.border,
            ),
            modifier = Modifier
                .weight(1f)
                .testTag("reframe-motion"),
        )
        Text(
            text = stringResource(R.string.reframe_quick),
            style = type.caption,
            color = colors.textSecondary,
        )
    }
}

/** While every shot is tracked: how far along, and a way to stop. */
@Composable
private fun Creating(progress: Float, onCancel: () -> Unit) {
    val colors = ClipraTheme.colors
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            text = stringResource(R.string.reframe_tracking, "${(progress * 100).roundToInt()}%"),
            style = ClipraTheme.typography.bodyStrong,
            color = colors.textPrimary,
        )
        LinearProgressIndicator(
            progress = { progress },
            color = colors.accent,
            trackColor = colors.border,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("reframe-progress"),
        )
        SecondaryButton(
            text = stringResource(R.string.reframe_cancel),
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
        )
        content()
    }
}

private fun Reframe.Follow.labelRes(): Int = when (this) {
    Reframe.Follow.Subject -> R.string.reframe_follow_subject
    Reframe.Follow.Face -> R.string.reframe_follow_face
    Reframe.Follow.Fixed -> R.string.reframe_follow_fixed
}

/** "00:07.12". */
private fun timecode(us: Long): String {
    val hundredths = us / 10_000
    return "%02d:%02d.%02d".format(hundredths / 6_000, (hundredths / 100) % 60, hundredths % 100)
}

/** What the new project is called after its format: "Lisbon weekend · Shorts". */
internal fun copyNameRes(ratio: AspectRatio): Int = when (ratio) {
    AspectRatio.Portrait9x16 -> R.string.reframe_name_shorts
    AspectRatio.Square1x1 -> R.string.reframe_name_square
    AspectRatio.Portrait4x5 -> R.string.reframe_name_portrait
    else -> R.string.reframe_name_wide
}

/** The board's source frame is 201 dp high at 16:9; a tall one stops a little higher. */
private const val MAX_SOURCE_HEIGHT = 300f
private const val RESULT_HEIGHT = 160
private const val DIM = 0.62f
private const val LABEL = 0.85f
