package tech.idct.clipra.feature.export

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChoiceCard
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.export.CaptionMode
import tech.idct.clipra.core.model.export.ExportQuality
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import tech.idct.clipra.core.model.export.formatEstimate

/** What the phone's encoders can do, for the options of the current project. */
data class EncoderSupport(
    /** Codecs with an encoder for the chosen output size. */
    val codecs: Set<VideoCodec> = VideoCodec.entries.toSet(),
    /** Resolutions some encoder of the chosen codec takes, in the project's ratio. */
    val resolutions: Set<Resolution> = Resolution.entries.toSet(),
)

/**
 * Export (Export board): resolution, frame rate, codec, quality and captions, with the size
 * estimate of the spec. Options the phone can't encode stay visible, disabled, with the reason.
 */
@Composable
fun ExportScreen(
    project: Project,
    settings: ExportSettings,
    support: EncoderSupport,
    onChange: (ExportSettings) -> Unit,
    onExport: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Export"),
    ) {
        ScreenHeader(title = stringResource(R.string.export_title_export), onNavigate = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            ProjectSummary(project)
            Section(stringResource(R.string.export_resolution)) {
                SegmentedControl(
                    segments = Resolution.entries.map { resolution ->
                        val size = settings.copy(resolution = resolution).outputSize(project)
                        Segment(
                            label = resolution.exportLabel(),
                            detail = "${size.width}×${size.height}",
                            enabled = resolution in support.resolutions,
                        )
                    },
                    selectedIndex = settings.resolution.ordinal,
                    onSelect = { onChange(settings.copy(resolution = Resolution.entries[it])) },
                    modifier = Modifier.fillMaxWidth().testTag("resolution"),
                )
            }
            Section(stringResource(R.string.export_frame_rate)) {
                SegmentedControl(
                    segments = FrameRate.entries.map { Segment(it.fps.toString()) },
                    selectedIndex = settings.frameRate.ordinal,
                    onSelect = { onChange(settings.copy(frameRate = FrameRate.entries[it])) },
                    monospace = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Section(stringResource(R.string.export_codec)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VideoCodec.entries.forEach { codec ->
                        val available = codec in support.codecs
                        val note = codec.noteRes(available)
                        ChoiceCard(
                            title = codec.label(),
                            subtitle = stringResource(note),
                            selected = codec == settings.codec,
                            enabled = available,
                            onClick = { onChange(settings.copy(codec = codec)) },
                            compact = true,
                            modifier = Modifier
                                .weight(1f)
                                .testTag("codec:${codec.name}"),
                        )
                    }
                }
            }
            Section(stringResource(R.string.export_quality)) {
                SegmentedControl(
                    segments = ExportQuality.entries.map { Segment(stringResource(it.labelRes())) },
                    selectedIndex = settings.quality.ordinal,
                    onSelect = { onChange(settings.copy(quality = ExportQuality.entries[it])) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Section(stringResource(R.string.export_captions)) {
                SegmentedControl(
                    segments = CaptionMode.entries.map { Segment(stringResource(it.labelRes())) },
                    selectedIndex = settings.captions.ordinal,
                    onSelect = { onChange(settings.copy(captions = CaptionMode.entries[it])) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Estimate(project, settings, onExport)
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = title,
            style = ClipraTheme.typography.sectionLabel,
            color = ClipraTheme.colors.textSecondary,
        )
        content()
    }
}

@Composable
private fun ProjectSummary(project: Project) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val ratio = project.format.ratio
        val scale = 56f / maxOf(ratio.width, ratio.height)
        Box(modifier = Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Box(
                modifier = Modifier
                    .size((ratio.width * scale).dp, (ratio.height * scale).dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(colors.surfaceRaised),
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = project.name, style = type.sectionTitle, color = colors.textPrimary)
            Text(
                text = stringResource(
                    R.string.export_project_line,
                    formatDuration(project.durationUs),
                    ratio.label,
                    pluralStringResource(
                        R.plurals.export_layers,
                        project.layers.size,
                        project.layers.size,
                    ),
                ),
                style = type.meta.copy(fontSize = type.chip.fontSize),
                color = colors.textSecondary,
            )
        }
    }
}

@Composable
private fun Estimate(project: Project, settings: ExportSettings, onExport: () -> Unit) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column {
        HorizontalDivider(thickness = 1.dp, color = colors.borderSubtle)
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.export_estimate),
                    style = type.body,
                    color = colors.textSecondary,
                    modifier = Modifier.padding(end = 8.dp),
                )
                Text(
                    text = stringResource(
                        R.string.export_megabytes,
                        formatEstimate(settings.estimatedMegabytes(project.durationUs)),
                    ),
                    style = type.metaStrong.copy(fontSize = type.screenTitle.fontSize),
                    color = colors.textPrimary,
                    modifier = Modifier.weight(1f).testTag("estimate"),
                )
                Text(
                    text = stringResource(
                        R.string.export_rate,
                        "%.1f".format(settings.videoMbps),
                        settings.codec.label(),
                    ),
                    style = type.meta.copy(fontSize = type.chip.fontSize),
                    color = colors.textSecondary,
                )
            }
            PrimaryButton(
                text = stringResource(R.string.export_to_gallery),
                onClick = onExport,
                leadingIcon = ClipraIcons.Export,
                enabled = project.durationUs > 0,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .testTag("export-to-gallery"),
            )
        }
    }
}

private fun Resolution.exportLabel(): String = when (this) {
    Resolution.HD -> "720p"
    Resolution.FHD -> "1080p"
    Resolution.QHD -> "2K"
    Resolution.UHD -> "4K"
}

private fun VideoCodec.label(): String = when (this) {
    VideoCodec.H264 -> "H.264"
    VideoCodec.HEVC -> "HEVC"
    VideoCodec.AV1 -> "AV1"
}

/** What the codec is good for, or that the phone has no encoder for it. */
private fun VideoCodec.noteRes(available: Boolean): Int = when {
    !available -> R.string.export_codec_unavailable
    this == VideoCodec.H264 -> R.string.export_codec_h264
    this == VideoCodec.HEVC -> R.string.export_codec_hevc
    else -> R.string.export_codec_av1
}

private fun ExportQuality.labelRes(): Int = when (this) {
    ExportQuality.Smaller -> R.string.export_quality_smaller
    ExportQuality.Recommended -> R.string.export_quality_recommended
    ExportQuality.High -> R.string.export_quality_high
}

private fun CaptionMode.labelRes(): Int = when (this) {
    CaptionMode.BurnIn -> R.string.export_captions_burn
    CaptionMode.Srt -> R.string.export_captions_srt
    CaptionMode.Off -> R.string.export_captions_off
}

/** "0:42". */
internal fun formatDuration(us: Long): String {
    val seconds = us / 1_000_000
    return "%d:%02d".format(seconds / 60, seconds % 60)
}
