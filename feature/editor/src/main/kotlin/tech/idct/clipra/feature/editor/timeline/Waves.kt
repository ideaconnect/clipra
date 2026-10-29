package tech.idct.clipra.feature.editor.timeline

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.designsystem.ClipraTheme
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.engine.media.Waveforms
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Project
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.sqrt

/** Waveforms for the timeline (#36), from the media cache, kept in memory while editing. */
class WaveformLoader(private val context: Context, private val cache: MediaCache) {
    private val waves = ConcurrentHashMap<String, Waveform>()
    private val media = ProjectMediaResolver(context, cache, forExport = false, maxImageSide = 0)

    suspend fun waveform(project: Project, mediaId: String): Waveform? {
        waves[mediaId]?.let { return it }
        val asset = project.asset(mediaId) ?: return null
        val wave = withContext(Dispatchers.IO) {
            runCatching { Waveforms.waveform(context, asset, cache, media) }.getOrNull()
        } ?: return null
        waves[mediaId] = wave
        return wave
    }
}

/**
 * A clip's sound as bars along it: the loudest moment of each slice of its media. Until the
 * waveform is ready (it starts at import), a flat line stands in. [muted] greys it out.
 */
@Composable
internal fun SoundWave(
    loader: WaveformLoader?,
    project: Project,
    clip: Clip,
    muted: Boolean,
    modifier: Modifier = Modifier,
) {
    val colors = ClipraTheme.colors
    val color = if (muted) colors.textDisabled else colors.clip.musicWave
    val source = clip.source
    val wave by produceState<Waveform?>(null, loader, source?.mediaId) {
        if (loader != null && source != null) value = loader.waveform(project, source.mediaId)
    }
    Canvas(modifier = modifier.background(colors.background.copy(alpha = 0.62f))) {
        val middle = size.height / 2
        val current = wave
        if (current == null || source == null) {
            drawLine(color, Offset(0f, middle), Offset(size.width, middle), 1.dp.toPx())
            return@Canvas
        }
        val step = 3.dp.toPx()
        val usPerPx = clip.durationUs * clip.speed.rate / size.width
        var x = 0f
        while (x < size.width) {
            val from = source.inPointUs + (x * usPerPx).toLong()
            val to = source.inPointUs + ((x + step) * usPerPx).toLong()
            // A square root lifts quiet speech so it reads at a glance.
            val half = maxOf(0.5f.dp.toPx(), sqrt(current.peakBetween(from, to)) * middle)
            drawLine(color, Offset(x, middle - half), Offset(x, middle + half), 2.dp.toPx())
            x += step
        }
    }
}
