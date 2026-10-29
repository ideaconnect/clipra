package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaFormat
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import java.io.File

/**
 * Turns a [RenderTimeline] into a Media3 [Composition] for Transformer (export) and
 * CompositionPlayer (preview).
 *
 * Video: one sequence holding a blank canvas image as long as the timeline, at the timeline's
 * frame rate; the [LayerCompositor] effect draws every layer onto it. Audio: one audio-only
 * sequence per layer that has video clips, with gaps where the layer is silent; Media3 mixes the
 * sequences.
 */
@OptIn(UnstableApi::class)
object CompositionFactory {
    fun create(
        context: Context,
        timeline: RenderTimeline,
        includeAudio: Boolean = true,
        gate: PauseGate? = null,
        live: LiveEdits? = null,
        listener: LayerCompositor.Listener? = null,
    ): Composition {
        val canvas = MediaItem.Builder()
            .setUri(Uri.fromFile(canvasImage(context)))
            .setImageDurationMs(timeline.durationUs / 1_000)
            .build()
        val video = EditedMediaItem.Builder(canvas)
            .setFrameRate(timeline.frameRate)
            .setDurationUs(timeline.durationUs)
            .setEffects(
                Effects(emptyList(), listOf(LayerCompositor(timeline, listener, gate, live))),
            )
            .build()
        val sequences = buildList {
            add(EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(video).build())
            if (includeAudio) {
                timeline.layers.mapNotNullTo(this) {
                    audioSequence(context, it, timeline.durationUs)
                }
            }
        }
        return Composition.Builder(sequences).build()
    }

    private fun audioSequence(
        context: Context,
        layer: RenderLayer,
        durationUs: Long,
    ): EditedMediaItemSequence? {
        if (layer.muted) return null
        // A held frame (rate 0) is silent; other speeds play their sound with M2-speed. Clips
        // turned all the way down (such as a video whose sound was extracted) add nothing.
        val clips = layer.clips.filter {
            it.soundSource() != null && it.rate > 0 && it.audio.volume > 0f
        }.sortedBy { it.startUs }
        if (clips.isEmpty()) return null
        val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_AUDIO))
        var position = 0L
        for (clip in clips) {
            val (path, inPointUs) = clip.soundSource()!!
            if (clip.startUs > position) sequence.addGap(clip.startUs - position)
            // Clips that overlap for a transition: the incoming clip's sound starts where the
            // outgoing clip ends.
            val start = maxOf(clip.startUs, position)
            if (start >= clip.endUs) continue
            val item = MediaItem.Builder()
                .setUri(MediaSources.uri(path))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionUs(inPointUs + (start - clip.startUs))
                        .setEndPositionUs(inPointUs + clip.durationUs)
                        .build(),
                )
                .build()
            // CompositionPlayer needs every item's duration: the file's, before clipping.
            val gain = ClipGain(
                volume = clip.audio.volume,
                fadeInUs = clip.audio.fadeInUs,
                fadeOutUs = clip.audio.fadeOutUs,
                durationUs = clip.durationUs,
                offsetUs = start - clip.startUs,
            )
            sequence.addItem(
                EditedMediaItem.Builder(item)
                    .setRemoveVideo(true)
                    .setDurationUs(fileDurationUs(context, path))
                    .setEffects(Effects(listOf(gain), emptyList()))
                    .build(),
            )
            position = clip.endUs
        }
        if (position < durationUs) sequence.addGap(durationUs - position)
        return sequence.build()
    }

    private fun RenderClip.soundSource(): Pair<String, Long>? = when (val source = source) {
        is RenderSource.Video -> if (source.hasAudio) source.path to source.inPointUs else null
        is RenderSource.Audio -> source.path to source.inPointUs
        is RenderSource.Image -> null
    }

    private fun fileDurationUs(context: Context, path: String): Long {
        val extractor = MediaSources.open(context, path)
        try {
            return (0 until extractor.trackCount).maxOf {
                val format = extractor.getTrackFormat(it)
                if (format.containsKey(MediaFormat.KEY_DURATION)) {
                    format.getLong(MediaFormat.KEY_DURATION)
                } else {
                    0L
                }
            }
        } finally {
            extractor.release()
        }
    }

    /** A tiny black PNG: the compositor replaces its pixels, so only its timing matters. */
    private fun canvasImage(context: Context): File {
        val file = File(context.cacheDir, "clipra-canvas.png")
        if (!file.exists()) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(android.graphics.Color.BLACK)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        return file
    }
}
