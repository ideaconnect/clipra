package tech.idct.clipra.feature.audio

import android.text.format.Formatter
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.ChoiceCard
import tech.idct.clipra.core.designsystem.component.ClipraSwitch
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.designsystem.component.SecondaryButton
import tech.idct.clipra.core.designsystem.component.Segment
import tech.idct.clipra.core.designsystem.component.SegmentedControl
import tech.idct.clipra.core.designsystem.component.SegmentedStyle
import tech.idct.clipra.core.designsystem.component.SwitchRow
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.designsystem.theme.ClipraDimens
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.engine.render.ClipGain
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Ducking
import tech.idct.clipra.core.model.LoudnessTargets
import tech.idct.clipra.core.model.NoiseReduction
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.model.mediaRangeOf
import tech.idct.clipra.core.model.normalizeDb
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelStatus
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
    val onVoice: (Boolean) -> Unit = {},
    val onEngine: (VoiceEngine) -> Unit = {},
    val onStrength: (Float) -> Unit = {},
    val onStrengthDone: () -> Unit = {},
    val onApplyToAll: () -> Unit = {},
    /** Noise reduction (#38): on or off, and its strength as the slider moves and is let go. */
    val onNoise: (Boolean) -> Unit = {},
    val onNoiseStrength: (Float) -> Unit = {},
    val onNoiseStrengthDone: () -> Unit = {},
    /** Ducking (#40): on or off, and its depth as the slider moves and is let go. */
    val onDucking: (Boolean) -> Unit = {},
    val onDuckingDepth: (Float) -> Unit = {},
    val onDuckingDepthDone: () -> Unit = {},
    /** Beat markers (#40) on or off. */
    val onBeats: (Boolean) -> Unit = {},
)

/**
 * The Audio board (#36): the clip's waveform with its loudness before and after, its volume
 * (0 to 200%), its fades, loudness normalization (#37), noise reduction (#38) and AI voice
 * cleanup (#39), ducking and beat markers (#40). [volume], [strength], [noiseStrength] and
 * [duckingDepth] follow their sliders while they move.
 */
@Composable
fun AudioScreen(
    state: AudioUiState,
    volume: Float,
    actions: AudioActions,
    modifier: Modifier = Modifier,
    strength: Float? = null,
    noiseStrength: Float? = null,
    duckingDepth: Float? = null,
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
                state.voice.cleanedWaveform.takeIf {
                    audio.voiceCleanup != null || audio.noiseReduction != null
                },
                audio.copy(volume = volume),
                state.recorded,
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
            NoiseReductionCard(audio.noiseReduction, noiseStrength, state.voice.gating, actions)
            VoiceCleanupCard(audio.voiceCleanup, strength, state.voice, actions)
            DuckingCard(audio.ducking, duckingDepth, actions)
            BeatsCard(audio.beats, state.voice.bpm, actions.onBeats)
            SecondaryButton(
                text = stringResource(R.string.audio_apply_all),
                onClick = actions.onApplyToAll,
                leadingIcon = ClipraIcons.Copy,
                outlined = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("audio-apply-all"),
            )
        }
    }
}

/**
 * AI voice cleanup (#39): the switch, the two engines and the strength. DeepFilterNet 3 says
 * whether it still has to be downloaded; the cleanup's progress replaces the explanation while
 * it runs. Off, the engines and strength are dimmed.
 */
@Composable
private fun VoiceCleanupCard(
    cleanup: VoiceCleanup?,
    liveStrength: Float?,
    voice: VoiceUiState,
    actions: AudioActions,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val on = cleanup != null
    val engine = cleanup?.engine ?: VoiceEngine.DeepFilterNet
    val strength = liveStrength ?: cleanup?.strength ?: VoiceCleanup.DEFAULT_STRENGTH
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClipraTheme.shapes.card)
            .background(colors.surface)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
            .testTag("audio-voice"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ClipraDimens.TouchTarget)
                .toggleable(value = on, role = Role.Switch, onValueChange = actions.onVoice)
                .testTag("audio-voice-switch"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(R.string.audio_voice_title),
                        style = type.bodyStrong,
                        color = colors.textPrimary,
                    )
                    Text(
                        text = stringResource(R.string.audio_on_device),
                        style = type.toolLabel,
                        color = colors.onDeviceText,
                        modifier = Modifier
                            .clip(ClipraTheme.shapes.thumb)
                            .background(colors.onDeviceBackground)
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    )
                }
                val cleaning = voice.cleaning
                Text(
                    text = if (on && cleaning != null) {
                        stringResource(R.string.audio_voice_cleaning, (cleaning * 100).roundToInt())
                    } else {
                        stringResource(R.string.audio_voice_detail)
                    },
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
            ClipraSwitch(checked = on, onCheckedChange = null)
        }
        Row(
            modifier = Modifier
                .alpha(if (on) 1f else 0.4f)
                .selectableGroup(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ChoiceCard(
                title = stringResource(R.string.audio_engine_dfn),
                subtitle = deepFilterNetDetail(voice.deepFilterNet),
                selected = on && engine == VoiceEngine.DeepFilterNet,
                onClick = { actions.onEngine(VoiceEngine.DeepFilterNet) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("audio-engine-dfn"),
            )
            ChoiceCard(
                title = stringResource(R.string.audio_engine_rnnoise),
                subtitle = stringResource(R.string.audio_engine_rnnoise_detail),
                selected = on && engine == VoiceEngine.RNNoise,
                onClick = { actions.onEngine(VoiceEngine.RNNoise) },
                modifier = Modifier
                    .weight(1f)
                    .testTag("audio-engine-rnnoise"),
            )
        }
        LabelledSlider(
            label = stringResource(R.string.audio_strength),
            value = strength,
            onValueChange = actions.onStrength,
            onValueChangeFinished = actions.onStrengthDone,
            valueText = "${(strength * 100).roundToInt()}",
            enabled = on,
            labelWidth = 80.dp,
            modifier = Modifier
                .height(44.dp)
                .testTag("audio-strength"),
        )
    }
}

/**
 * Noise reduction (#38): the switch and the strength, as the design draws it. Its progress
 * replaces the explanation while it runs; off, the strength is dimmed.
 */
@Composable
private fun NoiseReductionCard(
    noise: Float?,
    liveStrength: Float?,
    gating: Float?,
    actions: AudioActions,
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    val on = noise != null
    val strength = liveStrength ?: noise ?: NoiseReduction.DEFAULT_STRENGTH
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClipraTheme.shapes.card)
            .background(colors.surface)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 12.dp)
            .testTag("audio-noise"),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ClipraDimens.TouchTarget)
                .toggleable(value = on, role = Role.Switch, onValueChange = actions.onNoise)
                .testTag("audio-noise-switch"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = stringResource(R.string.audio_noise_title),
                    style = type.bodyStrong,
                    color = colors.textPrimary,
                )
                Text(
                    text = if (on && gating != null) {
                        stringResource(R.string.audio_noise_working, (gating * 100).roundToInt())
                    } else {
                        stringResource(R.string.audio_noise_detail)
                    },
                    style = type.caption,
                    color = colors.textSecondary,
                )
            }
            ClipraSwitch(checked = on, onCheckedChange = null)
        }
        LabelledSlider(
            label = stringResource(R.string.audio_strength),
            value = strength,
            onValueChange = actions.onNoiseStrength,
            onValueChangeFinished = actions.onNoiseStrengthDone,
            valueText = "${(strength * 100).roundToInt()}",
            enabled = on,
            labelWidth = 80.dp,
            modifier = Modifier
                .height(44.dp)
                .testTag("audio-noise-strength"),
        )
    }
}

/** Ducking (#40): the switch, as the design draws it, and how far the other clips go down. */
@Composable
private fun DuckingCard(depth: Float?, liveDepth: Float?, actions: AudioActions) {
    val on = depth != null
    val db = liveDepth ?: depth ?: Ducking.DEFAULT_DB
    SwitchCard(
        title = stringResource(R.string.audio_duck_title),
        detail = stringResource(R.string.audio_duck_detail, db.roundToInt()),
        on = on,
        onToggle = actions.onDucking,
        tag = "audio-duck",
    ) {
        if (on) {
            LabelledSlider(
                label = stringResource(R.string.audio_duck_depth),
                value = db,
                onValueChange = actions.onDuckingDepth,
                onValueChangeFinished = actions.onDuckingDepthDone,
                valueText = stringResource(R.string.audio_duck_db, db.roundToInt()),
                valueRange = Ducking.MIN_DB..Ducking.MAX_DB,
                labelWidth = 80.dp,
                modifier = Modifier
                    .height(44.dp)
                    .testTag("audio-duck-depth"),
            )
        }
    }
}

/** Beat markers (#40): the switch, with the tempo once it is known. */
@Composable
private fun BeatsCard(on: Boolean, bpm: Float?, onToggle: (Boolean) -> Unit) {
    SwitchCard(
        title = stringResource(R.string.audio_beats_title),
        detail = if (on && bpm != null && bpm > 0f) {
            stringResource(R.string.audio_beats_bpm, bpm.roundToInt())
        } else {
            stringResource(R.string.audio_beats_detail)
        },
        on = on,
        onToggle = onToggle,
        tag = "audio-beats",
    )
}

/** A card with a title, a line under it and a switch; the whole top row toggles. */
@Composable
private fun SwitchCard(
    title: String,
    detail: String,
    on: Boolean,
    onToggle: (Boolean) -> Unit,
    tag: String,
    content: @Composable () -> Unit = {},
) {
    val colors = ClipraTheme.colors
    val type = ClipraTheme.typography
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(ClipraTheme.shapes.card)
            .background(colors.surface)
            .padding(start = 12.dp, end = 12.dp, top = 4.dp, bottom = 4.dp)
            .testTag(tag),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = ClipraDimens.TouchTarget)
                .toggleable(value = on, role = Role.Switch, onValueChange = onToggle)
                .testTag("$tag-switch"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(text = title, style = type.bodyStrong, color = colors.textPrimary)
                Text(text = detail, style = type.caption, color = colors.textSecondary)
            }
            ClipraSwitch(checked = on, onCheckedChange = null)
        }
        content()
    }
}

/** DeepFilterNet 3's line: its size once in, or what its download is doing. */
@Composable
private fun deepFilterNetDetail(status: ModelStatus): String {
    val context = LocalContext.current
    val model = ModelCatalog.deepFilterNet
    return when (status) {
        is ModelStatus.Installed -> stringResource(
            R.string.audio_engine_dfn_detail,
            Formatter.formatShortFileSize(context, status.bytes),
        )

        is ModelStatus.Downloading -> stringResource(
            R.string.audio_engine_downloading,
            (status.bytes * 100 / status.total.coerceAtLeast(1)).toInt(),
        )

        is ModelStatus.Waiting -> stringResource(
            if (status.wifiOnly) R.string.audio_engine_wifi else R.string.audio_engine_network,
        )

        else -> stringResource(
            R.string.audio_engine_get,
            Formatter.formatShortFileSize(context, model.download?.bytes ?: 0),
        )
    }
}

/**
 * The clip's stretch of its media's waveform and its loudness: Before is the media as it is,
 * After is what the export plays, with the voice cleanup ([cleaned], once ready), volume,
 * fades and normalization (the limiter caps the bars at the ceiling). Quiet stretches are
 * dimmed.
 */
@Composable
private fun CompareCard(
    clip: Clip,
    waveform: Waveform?,
    cleaned: Waveform?,
    audio: AudioSettings,
    recorded: Loudness.Measurement?,
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
            Readout(
                if (after) levelsAfter(measured, audio) else recorded?.let(::levels),
                measuring,
            )
        }
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp),
        ) {
            val middle = size.height / 2
            val step = 4.8.dp.toPx()
            val clipUsPerPx = clip.durationUs / size.width
            val shown = if (after) cleaned ?: waveform else waveform
            val bars = FloatArray((size.width / step).toInt()) { bar ->
                val x = bar * step
                // The media under this bar, at the clip's speed (#22).
                val media = clip.mediaRangeOf(
                    (x * clipUsPerPx).toLong(),
                    ((x + step) * clipUsPerPx).toLong(),
                )
                val peak = media?.let { shown?.peakBetween(it.first, it.last) } ?: 0f
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

private fun decibels(value: Float) = String.format(Locale.getDefault(), "%.1f", value)

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

private fun seconds(us: Long) = String.format(Locale.getDefault(), "%.1f", us / 1_000_000.0)

private val FADES = listOf(0L, 250_000L, 500_000L, 1_000_000L, 1_500_000L, 2_000_000L, 3_000_000L)
