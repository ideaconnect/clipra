package tech.idct.clipra.feature.editor

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** A file in the Missing media sheet (#216): the project's media and how many clips play it. */
data class MissingFile(val asset: MediaAsset, val clips: Int)

/** The media of [project] in [missing], in the project's order, with their clips counted. */
fun missingFiles(project: Project, missing: Set<String>): List<MissingFile> =
    project.media.filter { it.id in missing }.map { asset ->
        val clips = project.layers.sumOf { layer ->
            layer.clips.count { it.source?.mediaId == asset.id }
        }
        MissingFile(asset, clips)
    }

/**
 * The Missing media sheet (#216), opened from a missing clip's label: one row per missing file
 * with what describes it (kind, length, size, date taken, clips) and Find file, which relinks
 * the media and so every clip of it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MissingMediaSheet(
    files: List<MissingFile>,
    onFind: (MediaAsset) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = ClipraTheme.colors.surfaceSunken,
    ) {
        MissingMediaList(
            files = files,
            onFind = onFind,
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp),
        )
    }
}

/** The sheet's content: its title, what finding a file does, and the files. */
@Composable
fun MissingMediaList(
    files: List<MissingFile>,
    onFind: (MediaAsset) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier.testTag("missing-media-sheet"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.editor_missing_title),
            style = type.screenTitle,
            color = colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.editor_missing_hint),
            style = type.caption,
            color = colors.textSecondary,
        )
        files.forEach { MissingRow(it, onFind) }
    }
}

@Composable
private fun MissingRow(file: MissingFile, onFind: (MediaAsset) -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val asset = file.asset
    val kind = stringResource(
        when (asset.kind) {
            MediaKind.Video -> R.string.editor_clip_video
            MediaKind.Photo -> R.string.editor_clip_photo
            MediaKind.Audio -> R.string.editor_clip_audio
        },
    )
    val size = asset.sizeBytes.takeIf { it > 0 }?.let {
        Formatter.formatShortFileSize(LocalContext.current, it)
    }
    val length = asset.durationUs.takeIf { asset.kind != MediaKind.Photo && it > 0 }
        ?.let(::formatLength)
    val taken = asset.takenAtMillis.takeIf { it > 0 }?.let {
        stringResource(R.string.editor_missing_taken, formatDate(it))
    }
    val clips = if (file.clips == 0) {
        stringResource(R.string.editor_missing_unused)
    } else {
        pluralStringResource(R.plurals.editor_layer_clips, file.clips, file.clips)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClipraTheme.shapes.button)
            .background(colors.surface)
            .padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)
            .testTag("missing:${asset.id}"),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(ClipraTheme.shapes.thumb)
                .background(colors.surfaceRaised),
            contentAlignment = Alignment.Center,
        ) {
            ClipraIcon(
                icon = when (asset.kind) {
                    MediaKind.Video -> ClipraIcons.Video
                    MediaKind.Photo -> ClipraIcons.Image
                    MediaKind.Audio -> ClipraIcons.Music
                },
                contentDescription = null,
                tint = colors.danger,
                size = 18.dp,
            )
        }
        // The button goes under the description, which keeps the whole width for it.
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = asset.displayName.ifEmpty { kind },
                style = type.bodyStrong,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(kind, length, size).joinToString(" · "),
                style = type.meta,
                color = colors.textSecondary,
            )
            Text(
                text = listOfNotNull(taken, clips).joinToString(" · "),
                style = type.meta,
                color = colors.textTertiary,
            )
            SecondaryButton(
                text = stringResource(R.string.editor_missing_find),
                onClick = { onFind(asset) },
                leadingIcon = ClipraIcons.Search,
                modifier = Modifier
                    .padding(top = 8.dp)
                    .testTag("find:${asset.id}"),
            )
        }
    }
}

/**
 * Asks before another file takes the place of missing media (#216): it isn't the file the
 * project used, and a shorter one moves the clips that don't fit in it.
 */
@Composable
fun RelinkDialog(candidate: RelinkCandidate, onUse: () -> Unit, onCancel: () -> Unit) {
    val colors = ClipraTheme.colors
    val name = candidate.asset.displayName.ifEmpty { stringResource(R.string.editor_relink_file) }
    val body = buildList {
        add(stringResource(R.string.editor_relink_body, name))
        if (candidate.refitted > 0) {
            add(
                if (candidate.clips == 1) {
                    stringResource(R.string.editor_relink_refit_one)
                } else {
                    stringResource(
                        R.string.editor_relink_refit,
                        candidate.refitted,
                        candidate.clips,
                    )
                },
            )
        }
    }.joinToString(" ")
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.editor_relink_title)) },
        text = { Text(body, modifier = Modifier.testTag("relink-body")) },
        confirmButton = {
            TextButton(onClick = onUse, modifier = Modifier.testTag("relink-use")) {
                Text(stringResource(R.string.editor_relink_use), color = colors.accent)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, modifier = Modifier.testTag("relink-cancel")) {
                Text(stringResource(R.string.editor_relink_cancel))
            }
        },
    )
}

/** "0:42", "2:05", "1:02:03": how long a missing video or sound is. */
internal fun formatLength(us: Long): String {
    val total = us / 1_000_000
    val hours = total / 3600
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, (total % 3600) / 60, total % 60)
    } else {
        "%d:%02d".format(total / 60, total % 60)
    }
}

/** "5 Nov 2026": the day a missing photo or video was taken, as the user's locale writes it. */
internal fun formatDate(millis: Long, locale: Locale = Locale.getDefault()): String =
    DateFormat.getDateInstance(DateFormat.MEDIUM, locale).format(Date(millis))
