package tech.idct.clipra.feature.audio

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.designsystem.component.HeaderNavigation
import tech.idct.clipra.core.designsystem.component.LabelledSlider
import tech.idct.clipra.core.designsystem.component.PrimaryButton
import tech.idct.clipra.core.designsystem.component.ScreenHeader
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.engine.render.ClipGain
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.ClipType
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** What the Audio screen can do. */
class AudioActions(
    val onBack: () -> Unit,
    val onVolume: (Float) -> Unit,
    val onVolumeDone: () -> Unit,
    val onAudio: (AudioSettings) -> Unit,
)

/**
 * The Audio board (#36): the clip's waveform, its volume (0 to 200%) and its fades. Loudness,
 * noise reduction, voice cleanup and ducking join with their issues. [volume] follows the
 * slider while it moves.
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
            WaveCard(
                state.waveform,
                clip.source?.inPointUs ?: 0,
                clip.durationUs,
                clip.speed.rate,
                audio.copy(volume = volume),
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
        }
    }
}

/** The clip's stretch of its media's waveform, scaled by the volume. */
@Composable
private fun WaveCard(
    waveform: Waveform?,
    inPointUs: Long,
    durationUs: Long,
    rate: Double,
    audio: AudioSettings,
) {
    // The engine's own level curve, so the card shows what will be heard.
    val gain = ClipGain(audio.volume, audio.fadeInUs, audio.fadeOutUs, durationUs)
    val colors = ClipraTheme.colors
    val shape = ClipraTheme.shapes.cardLarge
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(150.dp)
            .clip(shape)
            .background(colors.surface)
            .border(1.5.dp, colors.border, shape)
            .padding(horizontal = 24.dp, vertical = 20.dp)
            .testTag("audio-wave"),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val middle = size.height / 2
            val step = 4.dp.toPx()
            val usPerPx = durationUs * rate / size.width
            val clipUsPerPx = durationUs / size.width
            var x = 0f
            while (x < size.width) {
                val peak = waveform?.peakBetween(
                    inPointUs + (x * usPerPx).toLong(),
                    inPointUs + ((x + step) * usPerPx).toLong(),
                ) ?: 0f
                val level = peak * gain.gainAt((x * clipUsPerPx).toLong())
                val half = maxOf(1.dp.toPx(), sqrt(level.coerceAtMost(1f)) * middle)
                drawLine(
                    colors.clip.musicWave,
                    Offset(x, middle - half),
                    Offset(x, middle + half),
                    3.dp.toPx(),
                )
                x += step
            }
        }
    }
}

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
