package tech.idct.clipra.feature.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.export.LeftOutClip

/**
 * Asks before an export that would leave clips out (#212): their media can't be read, so the
 * video would have a hole where each of them is. Lists the clips (kind, layer, time, file) and
 * offers Export anyway, Cancel and, when the editor can find the files again (#216), Find files.
 */
@Composable
fun LeftOutDialog(
    clips: List<LeftOutClip>,
    onExportAnyway: () -> Unit,
    onCancel: () -> Unit,
    onFind: (() -> Unit)? = null,
) {
    val colors = ClipraTheme.colors
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.export_left_out_title)) },
        text = {
            Column(
                modifier = Modifier
                    .heightIn(max = 360.dp)
                    .verticalScroll(rememberScrollState())
                    .testTag("left-out"),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(stringResource(R.string.export_left_out_body))
                clips.forEach { LeftOutRow(it) }
            }
        },
        confirmButton = {
            TextButton(onClick = onExportAnyway, modifier = Modifier.testTag("left-out-export")) {
                Text(stringResource(R.string.export_left_out_export), color = colors.accent)
            }
        },
        dismissButton = {
            if (onFind != null) {
                TextButton(onClick = onFind, modifier = Modifier.testTag("left-out-find")) {
                    Text(stringResource(R.string.export_left_out_find))
                }
            }
            TextButton(onClick = onCancel, modifier = Modifier.testTag("left-out-cancel")) {
                Text(stringResource(R.string.export_left_out_cancel))
            }
        },
    )
}

/** "Video · L1 · 0:19-0:23", with the file's name under it when it is known. */
@Composable
private fun LeftOutRow(clip: LeftOutClip) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(modifier = Modifier.testTag("left-out:${clip.clipId}")) {
        Text(
            text = stringResource(
                R.string.export_left_out_clip,
                stringResource(clip.type.kindRes()),
                clip.layer,
                formatDuration(clip.startUs),
                // Up to the second it ends in, so even a short clip shows a stretch of time.
                formatDuration((clip.endUs + 999_999) / 1_000_000 * 1_000_000),
            ),
            style = type.bodyStrong,
            color = colors.textPrimary,
        )
        if (clip.fileName.isNotEmpty()) {
            Text(
                text = clip.fileName,
                style = type.meta,
                color = colors.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Only videos, photos and sounds have media to miss (`LeftOut`). */
private fun ClipType.kindRes(): Int = when (this) {
    ClipType.Photo -> R.string.export_left_out_photo
    ClipType.Audio -> R.string.export_left_out_sound
    else -> R.string.export_left_out_video
}
