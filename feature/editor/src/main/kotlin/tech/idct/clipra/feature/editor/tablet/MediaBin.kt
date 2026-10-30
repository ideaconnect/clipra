package tech.idct.clipra.feature.editor.tablet

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.feature.editor.R

/**
 * The tablet's media bin (TabletEditor board, #69): the project's photos, videos and sounds, a
 * tap puts one on the timeline at the playhead. Gallery opens the system's photo picker, which
 * shows the user's photos without Clipra reading the whole gallery.
 */
@Composable
internal fun MediaBin(
    project: Project,
    onImport: () -> Unit,
    onAdd: (mediaId: String) -> Unit,
    thumbnail: suspend (mediaId: String) -> ImageBitmap?,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    var source by rememberSaveable { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .padding(horizontal = 14.dp, vertical = 12.dp)
            .testTag("media-bin"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = stringResource(R.string.editor_tablet_media),
                style = type.bodyStrong,
                color = colors.textPrimary,
                modifier = Modifier.weight(1f),
            )
            Surface(
                onClick = onImport,
                modifier = Modifier
                    .height(36.dp)
                    .semantics { role = Role.Button }
                    .testTag("import"),
                shape = RoundedCornerShape(10.dp),
                color = colors.surfaceRaised,
                contentColor = colors.textPrimary,
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    ClipraIcon(icon = ClipraIcons.Add, contentDescription = null, size = 16.dp)
                    Text(text = stringResource(R.string.editor_tablet_import), style = type.chip)
                }
            }
        }
        SegmentedControl(
            segments = listOf(
                Segment(stringResource(R.string.editor_tablet_this_project)),
                Segment(stringResource(R.string.editor_tablet_gallery)),
            ),
            selectedIndex = source,
            onSelect = { source = it },
            style = SegmentedStyle.Tabs,
            modifier = Modifier.fillMaxWidth(),
        )
        if (source == 0) {
            if (project.media.isEmpty()) {
                Text(
                    text = stringResource(R.string.editor_tablet_bin_empty),
                    style = type.caption,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
            } else {
                val used = project.layers.flatMap { it.clips }.mapNotNull { it.source?.mediaId }
                    .toSet()
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(project.media, key = { it.id }) { asset ->
                        MediaTile(
                            asset = asset,
                            used = asset.id in used,
                            thumbnail = thumbnail,
                            onClick = { onAdd(asset.id) },
                        )
                    }
                }
            }
            Text(
                text = stringResource(R.string.editor_tablet_bin_hint),
                style = type.caption,
                color = colors.textTertiary,
            )
        } else {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.editor_tablet_gallery_hint),
                    style = type.caption,
                    color = colors.textSecondary,
                )
                Surface(
                    onClick = onImport,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .semantics { role = Role.Button }
                        .testTag("open-gallery"),
                    shape = RoundedCornerShape(10.dp),
                    color = colors.surfaceRaised,
                    contentColor = colors.textPrimary,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            text = stringResource(R.string.editor_tablet_open_gallery),
                            style = type.chip,
                        )
                    }
                }
            }
        }
    }
}

/** A picture of the media with its length (or IMG), a tick when it is on the timeline. */
@Composable
private fun MediaTile(
    asset: MediaAsset,
    used: Boolean,
    thumbnail: suspend (mediaId: String) -> ImageBitmap?,
    onClick: () -> Unit,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val name = asset.displayName.ifEmpty { asset.id.take(8) }
    val description = stringResource(R.string.editor_tablet_add_media, name)
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .semantics(mergeDescendants = true) {
                role = Role.Button
                contentDescription = description
            }
            .testTag("bin:$name"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(TILE_RATIO)
                .clip(RoundedCornerShape(8.dp))
                .background(
                    if (asset.kind == MediaKind.Audio) colors.clip.music else colors.surfaceRaised,
                ),
        ) {
            if (asset.kind == MediaKind.Audio) {
                ClipraIcon(
                    icon = ClipraIcons.Music,
                    contentDescription = null,
                    tint = colors.clip.musicWave,
                    modifier = Modifier.align(Alignment.Center),
                )
            } else {
                val picture by produceState<ImageBitmap?>(null, asset.id) {
                    value = thumbnail(asset.id)
                }
                picture?.let {
                    Image(
                        bitmap = it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            if (used) {
                Box(
                    modifier = Modifier
                        .padding(5.dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(colors.accent)
                        .align(Alignment.TopStart),
                    contentAlignment = Alignment.Center,
                ) {
                    ClipraIcon(
                        icon = ClipraIcons.Check,
                        contentDescription = null,
                        tint = colors.onAccent,
                        size = 12.dp,
                    )
                }
            }
            Text(
                text = if (asset.kind == MediaKind.Photo) {
                    stringResource(R.string.editor_tablet_photo_badge)
                } else {
                    formatLength(asset.durationUs)
                },
                style = type.clipLabel,
                color = colors.textPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(colors.background.copy(alpha = 0.75f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
            )
        }
        Text(
            text = name,
            style = type.caption,
            color = colors.textSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** "0:09": a media's length in minutes and seconds. */
internal fun formatLength(us: Long): String {
    val seconds = us / 1_000_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}

/** The board's tiles are about 106 × 62 dp. */
private const val TILE_RATIO = 1.7f
