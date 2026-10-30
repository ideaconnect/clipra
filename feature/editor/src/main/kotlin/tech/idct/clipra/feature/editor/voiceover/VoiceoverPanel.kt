package tech.idct.clipra.feature.editor.voiceover

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.IconAction
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.engine.media.Recording
import tech.idct.clipra.feature.editor.R
import tech.idct.clipra.feature.editor.VoiceoverState
import tech.idct.clipra.feature.editor.timeline.formatTimecode
import kotlin.math.roundToInt

/** What the voiceover panel can ask for. */
class VoiceoverActions(
    val onRecord: () -> Unit,
    val onStop: () -> Unit,
    val onDiscard: () -> Unit,
    val onClose: () -> Unit,
)

/**
 * Recording a voiceover (#41), in place of the tool strip: the red Record button and where the
 * recording goes (TabletAudio board), then the time, a level meter, Stop and Discard.
 */
@Composable
fun VoiceoverPanel(
    state: VoiceoverState,
    actions: VoiceoverActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
            .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 12.dp)
            .testTag("voiceover"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when {
            state.saving -> Saving()
            state.recording -> Recording(state, actions)
            else -> Ready(actions)
        }
    }
}

@Composable
private fun Header(content: @Composable () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(ClipraDimens.TouchTarget),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            content()
        }
        trailing()
    }
}

@Composable
private fun Ready(actions: VoiceoverActions) {
    val colors = ClipraTheme.colors
    Header(
        content = {
            Text(
                text = stringResource(R.string.editor_voiceover_title),
                style = ClipraTheme.typography.sectionLabel,
                color = colors.textPrimary,
            )
        },
        trailing = {
            IconAction(
                icon = ClipraIcons.Close,
                contentDescription = stringResource(R.string.editor_voiceover_close),
                onClick = actions.onClose,
                modifier = Modifier.testTag("voiceover-close"),
            )
        },
    )
    Column(
        modifier = Modifier.padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        RecordButton(
            text = stringResource(R.string.editor_voiceover_record),
            stop = false,
            onClick = actions.onRecord,
            modifier = Modifier.testTag("voiceover-record"),
        )
        Text(
            text = stringResource(R.string.editor_voiceover_hint),
            style = ClipraTheme.typography.caption,
            color = colors.textTertiary,
        )
    }
}

@Composable
private fun Recording(state: VoiceoverState, actions: VoiceoverActions) {
    val colors = ClipraTheme.colors
    Header(
        content = {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(colors.record.dot, CircleShape),
            )
            Text(
                text = stringResource(R.string.editor_voiceover_recording),
                style = ClipraTheme.typography.sectionLabel,
                color = colors.record.text,
            )
        },
        trailing = {
            Text(
                text = formatTimecode(state.elapsedUs),
                style = ClipraTheme.typography.metaStrong,
                color = colors.textPrimary,
                modifier = Modifier
                    .padding(end = 8.dp)
                    .testTag("voiceover-time"),
            )
        },
    )
    Column(
        modifier = Modifier.padding(end = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        LevelMeter(state.levels)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton(
                text = stringResource(R.string.editor_voiceover_discard),
                onClick = actions.onDiscard,
                modifier = Modifier
                    .weight(1f)
                    .height(RecordButtonHeight)
                    .testTag("voiceover-discard"),
                leadingIcon = ClipraIcons.Delete,
            )
            RecordButton(
                text = stringResource(R.string.editor_voiceover_stop),
                stop = true,
                onClick = actions.onStop,
                modifier = Modifier
                    .weight(1f)
                    .testTag("voiceover-stop"),
            )
        }
    }
}

@Composable
private fun Saving() {
    val colors = ClipraTheme.colors
    Header(
        content = {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = colors.accent,
                strokeWidth = 2.dp,
            )
            Text(
                text = stringResource(R.string.editor_voiceover_saving),
                style = ClipraTheme.typography.sectionLabel,
                color = colors.textPrimary,
                modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
            )
        },
        trailing = {},
    )
}

/** The red button of the TabletAudio board: a dot to record, a square to stop. */
@Composable
private fun RecordButton(
    text: String,
    stop: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Surface(
        onClick = onClick,
        modifier = modifier
            .fillMaxWidth()
            .height(RecordButtonHeight)
            .semantics { role = Role.Button },
        shape = ClipraTheme.shapes.button,
        color = colors.record.background,
        contentColor = colors.record.text,
        border = BorderStroke(1.5.dp, colors.record.border),
    ) {
        Row(
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .background(
                        colors.record.dot,
                        if (stop) RoundedCornerShape(2.dp) else CircleShape,
                    ),
            )
            Spacer(Modifier.width(10.dp))
            Text(text = text, style = ClipraTheme.typography.button)
        }
    }
}

/**
 * The last two seconds of levels as bars, newest on the right, from silence (-60 dBFS) to
 * full scale; TalkBack hears the current level.
 */
@Composable
private fun LevelMeter(levels: List<Float>) {
    val colors = ClipraTheme.colors
    val current = levels.lastOrNull() ?: Recording.SILENCE_DB
    val description = stringResource(R.string.editor_voiceover_level, current.roundToInt())
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(36.dp)
            .background(colors.surface, ClipraTheme.shapes.thumb)
            .semantics { contentDescription = description }
            .testTag("voiceover-meter"),
    ) {
        val bar = 3.dp.toPx()
        val gap = 2.dp.toPx()
        val count = (size.width / (bar + gap)).toInt()
        val shown = levels.takeLast(count)
        val middle = size.height / 2
        shown.forEachIndexed { index, db ->
            val x = size.width - (shown.size - index) * (bar + gap)
            val share = ((db - Recording.SILENCE_DB) / -Recording.SILENCE_DB).coerceIn(0f, 1f)
            val height = (size.height - 8.dp.toPx()) * share + 2.dp.toPx()
            drawRoundRect(
                color = colors.clip.voiceWave,
                topLeft = Offset(x, middle - height / 2),
                size = Size(bar, height),
                cornerRadius = CornerRadius(bar / 2),
            )
        }
    }
}

/** The Record button's height on the TabletAudio board; Discard beside it matches. */
private val RecordButtonHeight = 52.dp
