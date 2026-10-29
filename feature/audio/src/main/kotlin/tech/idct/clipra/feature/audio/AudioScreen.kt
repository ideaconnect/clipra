package tech.idct.clipra.feature.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChoiceCard
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.engine.render.ClipGain
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.LoudnessTargets
import tech.idct.clipra.core.model.normalizeDb
import java.util.Locale
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the Audio screen can do. */
class AudioActions(
    val onBack: () -> Unit,
    val onVolume: (Float) -> Unit,
    val onVolumeDone: () -> Unit,
    val onAudio: (AudioSettings) -> Unit,
    val onLoudnessTarget: (Int?) -> Unit,
)

/**
 * The Audio board (#36): the clip's waveform with its loudness before and after, its volume
 * (0 to 200%), its fades and loudness normalization (#37). Noise reduction, voice cleanup and
 * ducking join with their issues. [volume] follows the slider while it moves.
 */
@Composable
fun AudioScreen(
    state: AudioUiState,
    volume: Float,
    actions: AudioActions,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val project = state.project ?: return
    val clip = state.clip ?: return
    val audio = clip.audio
    val name = clip.source?.let { project.asset(it.mediaId) }?.displayName.orEmpty()
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background)
            .safeDrawingPadding()
            .testTag("screen:Audio"),
    ) {
        ScreenHeader(
            title = stringResource(R.string.audio_title_audio),
            subtitle = if (clip.type == ClipType.Audio) {
                name
            } else {
                stringResource(R.string.audio_clip_sound, name)
            },
            navigation = HeaderNavigation.Back,
            onNavigate = actions.onBack,
            actions = {
                PrimaryButton(
                    text = stringResource(R.string.audio_done),
                    onClick = actions.onBack,
                    compact = true,
                    modifier = Modifier.testTag("audio-done"),
                )
            },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            CompareCard(
                clip,
                state.waveform,
                audio.copy(volume = volume),
                state.measured,
                state.measuring,
            )
            LabelledSlider(
                label = stringResource(R.string.audio_volume),
                value = volume,
                onValueChange = actions.onVolume,
                onValueChangeFinished = actions.onVolumeDone,
                valueText = stringResource(R.string.audio_percent, (volume * 100).roundToInt()),
                valueRange = 0f..2f,
                labelWidth = 80.dp,
                modifier = Modifier
                    .height(52.dp)
                    .testTag("audio-volume"),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                FadeField(
                    label = stringResource(R.string.audio_fade_in),
                    valueUs = audio.fadeInUs,
                    maxUs = clip.durationUs,
                    onPick = { actions.onAudio(audio.copy(fadeInUs = it)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("audio-fade-in"),
                )
                FadeField(
                    label = stringResource(R.string.audio_fade_out),
                    valueUs = audio.fadeOutUs,
                    maxUs = clip.durationUs,
                    onPick = { actions.onAudio(audio.copy(fadeOutUs = it)) },
                    modifier = Modifier
                        .weight(1f)
                        .testTag("audio-fade-out"),
                )
            }
            NormalizeCard(audio, state.measured, state.measuring, actions.onLoudnessTarget)
        }
    }
}

/**
 * The clip's stretch of its media's waveform and its loudness: Before is the media as it is,
 * After is what the export plays, with the volume, fades and normalization (the limiter caps
 * the bars at the ceiling). Quiet stretches are dimmed.
 */
@Composable
private fun CompareCard(
    clip: Clip,
    waveform: Waveform?,
    audio: AudioSettings,
    measured: Loudness.Measurement?,
    measuring: Boolean,
) {
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.card
    var after by rememberSaveable { mutableStateOf(true) }
    // The engine's own level curve, so the card shows what will be heard.
    val gain = ClipGain(audio.volume, audio.fadeInUs, audio.fadeOutUs, clip.durationUs)
    val normalize = 10f.pow(audio.normalizeDb / 20)
    val ceiling = if (audio.loudnessTarget != null) PEAK_CEILING else Float.MAX_VALUE
    val inPointUs = clip.source?.inPointUs ?: 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(colors.surfaceSunken)
            .border(1.dp, colors.borderSubtle, shape)
            .padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 12.dp)
            .testTag("audio-wave"),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SegmentedControl(
                segments = listOf(
                    Segment(stringResource(R.string.audio_before)),
                    Segment(stringResource(R.string.audio_after)),
                ),
                selectedIndex = if (after) 1 else 0,
                onSelect = { after = it == 1 },
                style = SegmentedStyle.Tabs,
                modifier = Modifier
                    .width(160.dp)
                    .testTag("audio-compare"),
            )
            Spacer(Modifier.weight(1f))
            Readout(if (after) levelsAfter(measured, audio) else measured?.let(::levels), measuring)
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            val middle = size.height / 2
            val step = 4.8.dp.toPx()
            val usPerPx = clip.durationUs * clip.speed.rate / size.width
            val clipUsPerPx = clip.durationUs / size.width
            val bars = FloatArray((size.width / step).toInt()) { bar ->
                val x = bar * step
                val peak = waveform?.peakBetween(
                    inPointUs + (x * usPerPx).toLong(),
                    inPointUs + ((x + step) * usPerPx).toLong(),
                ) ?: 0f
                if (after) {
                    minOf(peak * gain.gainAt((x * clipUsPerPx).toLong()) * normalize, ceiling)
                } else {
                    peak
                }
            }
            val quiet = (bars.maxOrNull() ?: 0f) * QUIET
            val loud = if (after) colors.clip.musicWave else colors.textSecondaryAlt
            val dim = if (after) colors.clip.musicWave.copy(alpha = 0.45f) else colors.textDisabled
            bars.forEachIndexed { bar, level ->
                val x = bar * step + 1.4.dp.toPx()
                val half = maxOf(1.dp.toPx(), sqrt(level.coerceAtMost(1f)) * (middle - 1.dp.toPx()))
                drawLine(
                    if (level > quiet) loud else dim,
                    Offset(x, middle - half),
                    Offset(x, middle + half),
                    2.8.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
        }
    }
}

/** "-14.0 LUFS" over "peak -1.0 dBTP", or why there is no number. */
@Composable
private fun Readout(levels: Levels?, measuring: Boolean) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(horizontalAlignment = Alignment.End, modifier = Modifier.testTag("audio-readout")) {
        if (levels == null) {
            Text(
                text = stringResource(
                    when {
                        measuring -> R.string.audio_measuring
                        else -> R.string.audio_no_sound
                    },
                ),
                style = type.meta,
                color = colors.textSecondary,
            )
            return@Column
        }
        Text(
            text = stringResource(R.string.audio_lufs, decibels(levels.lufs)),
            style = type.metaStrong,
            color = colors.textPrimary,
        )
        Text(
            text = stringResource(R.string.audio_peak, decibels(levels.peakDb)),
            style = type.meta,
            color = colors.textSecondary,
        )
    }
}

/** The Normalize loudness card: a switch and the three targets, ready once it is measured. */
@Composable
private fun NormalizeCard(
    audio: AudioSettings,
    measured: Loudness.Measurement?,
    measuring: Boolean,
    onTarget: (Int?) -> Unit,
) {
    val colors = ClipraTheme.colors
    val target = audio.loudnessTarget
    var lastTarget by rememberSaveable { mutableIntStateOf(target ?: LoudnessTargets.SOCIAL) }
    val ready = measured != null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClipraTheme.shapes.card)
            .background(colors.surface)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        SwitchRow(
            title = stringResource(R.string.audio_normalize),
            subtitle = stringResource(
                when {
                    ready -> R.string.audio_normalize_detail
                    measuring -> R.string.audio_normalize_measuring
                    else -> R.string.audio_normalize_silent
                },
            ),
            checked = target != null,
            onCheckedChange = { onTarget(if (it) lastTarget else null) },
            enabled = ready,
            modifier = Modifier.testTag("audio-normalize"),
        )
        Row(
            modifier = Modifier
                .alpha(if (target != null) 1f else 0.4f)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            TARGETS.forEach { (lufs, use) ->
                ChoiceCard(
                    title = stringResource(R.string.audio_target_lufs, lufs),
                    subtitle = stringResource(use),
                    selected = (target ?: lastTarget) == lufs,
                    onClick = {
                        lastTarget = lufs
                        onTarget(lufs)
                    },
                    enabled = ready,
                    monospaceTitle = true,
                    compact = true,
                    centered = true,
                    modifier = Modifier
                        .weight(1f)
                        .testTag("audio-target$lufs"),
                )
            }
        }
    }
}

/** A readout's numbers: integrated loudness in LUFS and true peak in dBTP. */
internal data class Levels(val lufs: Float, val peakDb: Float)

internal fun levels(measured: Loudness.Measurement) = Levels(measured.lufs, measured.truePeakDb)

/**
 * The clip's loudness as exported: its volume and normalization move the measured levels, and
 * with normalization the limiter holds the peak at the ceiling. Null when muted or unmeasured.
 */
internal fun levelsAfter(measured: Loudness.Measurement?, audio: AudioSettings): Levels? {
    if (measured == null || audio.volume <= 0f) return null
    val gain = 20 * log10(audio.volume) + audio.normalizeDb
    val peak = measured.truePeakDb + gain
    val limited = if (audio.loudnessTarget != null) {
        minOf(peak, LoudnessTargets.PEAK_CEILING_DB)
    } else {
        peak
    }
    return Levels(measured.lufs + gain, limited)
}

private fun decibels(value: Float) = String.format(Locale.ROOT, "%.1f", value)

private val TARGETS = listOf(
    LoudnessTargets.SOCIAL to R.string.audio_target_social,
    LoudnessTargets.PODCAST to R.string.audio_target_podcast,
    LoudnessTargets.BROADCAST to R.string.audio_target_broadcast,
)

private val PEAK_CEILING = 10f.pow(LoudnessTargets.PEAK_CEILING_DB / 20)

// Bars a tenth of the loudest (20 dB down) or quieter read as pauses.
private const val QUIET = 0.1f

/** "Fade in   0.5 s": tap for the lengths that fit the clip. */
@Composable
private fun FadeField(
    label: String,
    valueUs: Long,
    maxUs: Long,
    onPick: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    var open by remember { mutableStateOf(false) }
    Box(modifier = modifier) {
        Surface(
            onClick = { open = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            shape = ClipraTheme.shapes.button,
            color = colors.surface,
            contentColor = colors.textPrimary,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = label,
                    style = type.body,
                    color = colors.textSecondary,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    text = stringResource(R.string.audio_seconds, seconds(valueUs)),
                    style = type.metaStrong,
                )
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            FADES.filter { it <= maxUs }.forEach { us ->
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.audio_seconds, seconds(us))) },
                    onClick = {
                        open = false
                        onPick(us)
                    },
                )
            }
        }
    }
}

private fun seconds(us: Long) = String.format(Locale.ROOT, "%.1f", us / 1_000_000.0)

private val FADES = listOf(0L, 250_000L, 500_000L, 1_000_000L, 1_500_000L, 2_000_000L, 3_000_000L)
