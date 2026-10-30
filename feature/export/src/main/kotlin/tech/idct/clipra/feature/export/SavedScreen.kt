package tech.idct.clipra.feature.export

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.view.SurfaceView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ClipraIcon
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ProCard
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.engine.export.ExportState
import java.util.Locale

/**
 * Saved to Gallery (ExportDone board): the video with play, its details, Share, Open Gallery,
 * the Clipra Pro card for free users and Back to editing.
 */
@Composable
fun SavedScreen(
    saved: ExportState.Done,
    showPro: Boolean,
    proPrice: String?,
    onShare: () -> Unit,
    onOpenGallery: () -> Unit,
    onPro: () -> Unit,
    onBackToEditing: () -> Unit,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit = { VideoPreview(Uri.parse(saved.uri)) },
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:ExportDone"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.export_title_export_done),
            navigation = HeaderNavigation.Close,
            onNavigate = onBackToEditing,
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val ratio = saved.width.toFloat() / saved.height
                val height = if (ratio < 1) 266f else 150f / ratio
                Box(
                    modifier = Modifier
                        .size((height * ratio).dp, height.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface),
                ) {
                    preview()
                }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(colors.onDeviceBackground),
                        contentAlignment = Alignment.Center,
                    ) {
                        ClipraIcon(
                            icon = ClipraIcons.Check,
                            contentDescription = null,
                            tint = colors.onDeviceText,
                        )
                    }
                    Text(
                        text = saved.fileName,
                        style = type.sectionTitle,
                        color = colors.textPrimary,
                    )
                    val mono = type.meta.copy(fontSize = type.chip.fontSize)
                    val length = formatDuration(saved.durationUs)
                    val size = formatSize(saved.sizeBytes)
                    val frame = "${saved.width}×${saved.height}"
                    Text(
                        text = "$length · $size",
                        style = mono,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = "$frame · ${saved.settings.frameRate.fps} fps",
                        style = mono,
                        color = colors.textSecondary,
                    )
                    Text(
                        text = saved.settings.codecLabel(),
                        style = mono,
                        color = colors.textSecondary,
                    )
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                PrimaryButton(
                    text = stringResource(R.string.export_share),
                    onClick = onShare,
                    leadingIcon = ClipraIcons.Share,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("share"),
                )
                SecondaryButton(
                    text = stringResource(R.string.export_open_gallery),
                    onClick = onOpenGallery,
                    leadingIcon = ClipraIcons.Image,
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 56.dp),
                )
            }
            if (showPro) {
                ProCard(
                    title = stringResource(R.string.export_pro_title),
                    subtitle = proPrice?.let {
                        stringResource(R.string.export_pro_subtitle_price, it)
                    } ?: stringResource(R.string.export_pro_subtitle),
                    onClick = onPro,
                    modifier = Modifier.fillMaxWidth(),
                    action = {
                        ClipraIcon(
                            icon = ClipraIcons.ChevronRight,
                            contentDescription = null,
                            tint = colors.accent,
                        )
                        Spacer(Modifier.size(8.dp))
                    },
                )
            }
        }
        SecondaryButton(
            text = stringResource(R.string.export_back_to_editing),
            onClick = onBackToEditing,
            outlined = true,
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
                .heightIn(min = 56.dp),
        )
    }
}

/** The saved video: its first frame, and inline playback after a tap on play. */
@Composable
private fun VideoPreview(uri: Uri) {
    var playing by remember { mutableStateOf(false) }
    if (playing) {
        InlinePlayer(uri, onEnded = { playing = false })
    } else {
        FirstFrame(uri, onPlay = { playing = true })
    }
}

@Composable
private fun InlinePlayer(uri: Uri, onEnded: () -> Unit) {
    val context = LocalContext.current
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(uri))
            prepare()
            play()
        }
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) onEnded()
            }
        }
        player.addListener(listener)
        onDispose { player.release() }
    }
    AndroidView(
        factory = { SurfaceView(it).also(player::setVideoSurfaceView) },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun FirstFrame(uri: Uri, onPlay: () -> Unit) {
    val context = LocalContext.current
    val frame by produceState<Bitmap?>(null, uri) { value = firstFrame(context, uri) }
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        frame?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
        Surface(
            onClick = onPlay,
            modifier = Modifier
                .size(52.dp)
                .semantics { role = Role.Button }
                .testTag("play"),
            shape = CircleShape,
            color = ClipraTheme.colors.background.copy(alpha = 0.7f),
            contentColor = ClipraTheme.colors.textPrimary,
        ) {
            Box(contentAlignment = Alignment.Center) {
                ClipraIcon(
                    icon = ClipraIcons.Play,
                    contentDescription = stringResource(R.string.export_play),
                )
            }
        }
    }
}

private suspend fun firstFrame(context: Context, uri: Uri): Bitmap? = withContext(Dispatchers.IO) {
    try {
        MediaMetadataRetriever().use { retriever ->
            retriever.setDataSource(context, uri)
            retriever.getFrameAtTime(0)
        }
    } catch (_: RuntimeException) {
        null
    }
}

/** "64.3 MB", in decimal units. */
internal fun formatSize(bytes: Long, locale: Locale = Locale.getDefault()): String =
    String.format(locale, "%.1f MB", bytes / 1_000_000.0)

/** Opens the system share sheet with the video. */
fun shareVideo(context: Context, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND)
        .setType("video/mp4")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** Opens the video in the gallery or the default video player. */
fun openInGallery(context: Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "video/mp4")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        // No app can show videos; nothing to open.
    }
}
