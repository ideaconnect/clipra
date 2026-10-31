package tech.idct.clipra.feature.export

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.ExportSettings

/**
 * What the tablet's Export shows beside the options (#70): the [cover] frame (at [coverUs]) and a
 * [strip] of frames along the project; a tap on the strip picks the cover there ([onCover]).
 */
class ExportPreview(
    val cover: Bitmap? = null,
    val strip: List<Bitmap> = emptyList(),
    val coverUs: Long = 0,
    val onCover: (Long) -> Unit = {},
)

/**
 * Export on a tablet (#70, TabletExport board): the cover frame large with its size and frame
 * rate, the filmstrip with where the cover is, and Also save on the left; the options, the
 * estimate and Export to Gallery on the right.
 */
@Composable
internal fun TabletExport(
    project: Project,
    settings: ExportSettings,
    support: EncoderSupport,
    onChange: (ExportSettings) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    ads: ExportAdsLine?,
    preview: ExportPreview,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val size = settings.outputSize(project)
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Export"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.export_title_export),
            onNavigate = onBack,
            actions = {
                Text(
                    text = project.name,
                    style = type.bodyStrong,
                    color = colors.textPrimary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = stringResource(
                        R.string.export_project_line,
                        formatDuration(project.durationUs),
                        project.format.ratio.label,
                        pluralStringResource(
                            R.plurals.export_layers,
                            project.layers.size,
                            project.layers.size,
                        ),
                    ),
                    style = type.meta,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(end = 16.dp),
                )
            },
        )
        Row(modifier = Modifier.weight(1f)) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 40.dp, vertical = 30.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val ratio = size.width.toFloat() / size.height
                // As wide as the pane, and a tall project no taller than the board's frame.
                Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Box(
                        modifier = Modifier
                            .heightIn(max = MAX_COVER_HEIGHT.dp)
                            .aspectRatio(ratio)
                            .clip(RoundedCornerShape(8.dp))
                            .background(colors.surface)
                            .testTag("export-cover"),
                    ) {
                        preview.cover?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                        Text(
                            text = "${size.width} × ${size.height} · ${settings.frameRate.fps} fps",
                            style = type.meta,
                            color = colors.textPrimary,
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .padding(14.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(colors.background.copy(alpha = LABEL))
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        )
                    }
                }
                Filmstrip(project.durationUs, preview)
                AlsoSave(project, settings, onChange)
            }
            Column(
                modifier = Modifier
                    .width(OPTIONS_WIDTH.dp)
                    .fillMaxHeight()
                    .background(colors.background),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    ExportOptions(project, settings, support, onChange)
                }
                Estimate(project, settings, onExport, ads)
                Text(
                    text = stringResource(R.string.export_tablet_note),
                    style = type.caption,
                    color = colors.textSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 24.dp, end = 24.dp, bottom = 16.dp),
                )
            }
        }
    }
}

/** Frames along the project, where the cover is, and its time; a tap moves the cover. */
@Composable
private fun Filmstrip(durationUs: Long, preview: ExportPreview) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val accent = colors.accent
    val description = stringResource(R.string.export_cover_strip)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(STRIP_HEIGHT.dp)
                .clip(RoundedCornerShape(6.dp))
                .background(colors.surface)
                .semantics { contentDescription = description }
                .pointerInput(durationUs) {
                    detectTapGestures { at ->
                        val share = (at.x / size.width).coerceIn(0f, 1f)
                        preview.onCover((durationUs * share).toLong())
                    }
                }
                .testTag("export-strip"),
        ) {
            Row(Modifier.fillMaxSize()) {
                preview.strip.forEach { frame ->
                    Image(
                        bitmap = frame.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            }
            Canvas(Modifier.fillMaxSize()) {
                if (durationUs <= 0) return@Canvas
                val x = size.width * (preview.coverUs.toFloat() / durationUs).coerceIn(0f, 1f)
                drawLine(accent, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
        Row {
            Text(text = clock(0), style = type.meta, color = colors.textSecondary)
            Text(
                text = clock(preview.coverUs),
                style = type.meta,
                color = colors.textSecondary,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
            )
            Text(text = clock(durationUs), style = type.meta, color = colors.textSecondary)
        }
    }
}

/** "00:07.12". */
private fun clock(us: Long): String {
    val hundredths = us / 10_000
    return "%02d:%02d.%02d".format(hundredths / 6_000, (hundredths / 100) % 60, hundredths % 100)
}

/** The options' column, as on the TabletExport board. */
private const val OPTIONS_WIDTH = 380
private const val STRIP_HEIGHT = 50

/** The board's 16:9 cover is 920 × 518 dp; nothing gets taller. */
private const val MAX_COVER_HEIGHT = 518
private const val LABEL = 0.75f
