package tech.idct.clipra.core.engine.media

import android.content.Context
import tech.idct.clipra.core.engine.render.MediaResolver
import tech.idct.clipra.core.model.MediaAsset
import java.io.File
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * A sound's outline (#36): the loudest sample of every [BUCKET_US] slice, 0 to 255, for the
 * timeline and the Audio screen.
 */
class Waveform(val peaks: ByteArray) {
    /** The peak, 0 to 1, of the slice at [timeUs] in the media's own time. */
    fun peakAt(timeUs: Long): Float {
        val index = (timeUs / BUCKET_US).toInt()
        if (index !in peaks.indices) return 0f
        return (peaks[index].toInt() and 0xFF) / 255f
    }

    /** The highest peak, 0 to 1, from [fromUs] to [toUs]. */
    fun peakBetween(fromUs: Long, toUs: Long): Float {
        val first = (fromUs / BUCKET_US).toInt().coerceAtLeast(0)
        val last = (toUs / BUCKET_US).toInt().coerceAtMost(peaks.size - 1)
        var peak = 0
        for (i in first..last) peak = maxOf(peak, peaks[i].toInt() and 0xFF)
        return peak / 255f
    }

    companion object {
        /** 20 ms slices: 50 a second, 3,000 bytes a minute. */
        const val BUCKET_US = 20_000L
    }
}

/**
 * Extracts waveforms once per media and keeps them in the media cache. Long sounds decode in
 * slices side by side, one decoder each, so the timeline gets them quickly.
 */
object Waveforms {
    /** The waveform of [asset], extracted on first use; null for media without sound. */
    fun waveform(
        context: Context,
        asset: MediaAsset,
        cache: MediaCache,
        media: MediaResolver,
    ): Waveform? {
        val file = cache.waveform(asset.id)
        if (file.exists()) return Waveform(file.readBytes())
        val source = media.source(asset) ?: return null
        return waveform(context, source, asset.durationUs, file)
    }

    /**
     * The waveform of the sound in [source] ([durationUs] long, 0 when unknown), kept in [file];
     * such as a cleaned voice (#39). Null when it has no sound.
     */
    fun waveform(context: Context, source: String, durationUs: Long, file: File): Waveform? {
        if (file.exists()) return Waveform(file.readBytes())
        val slices = if (durationUs >= SLICE_FROM_US) SLICES else 1
        // Unknown lengths grow the buckets as they go, in one slice.
        val known = durationUs > 0
        var peaks = IntArray(if (known) (durationUs / Waveform.BUCKET_US + 1).toInt() else 0)
        val sliceUs = if (known) durationUs / slices else Long.MAX_VALUE
        var sound = true
        val threads = (0 until slices).map { slice ->
            thread(name = "clipra-waveform-$slice") {
                val from = slice * sliceUs
                val to = if (slice == slices - 1) Long.MAX_VALUE else from + sliceUs
                val format = AudioDecoder.decode(context, source, from, to) {
                        samples,
                        count,
                        format,
                        timeUs,
                    ->
                    if (!known) {
                        val frames = count.toLong() / format.channels
                        val endUs = timeUs + frames * 1_000_000 / format.sampleRate
                        val needed = (endUs / Waveform.BUCKET_US).toInt() + 1
                        if (needed > peaks.size) peaks = peaks.copyOf(needed)
                    }
                    fold(peaks, samples, count, format, timeUs)
                }
                if (format == null) sound = false
            }
        }
        threads.forEach { it.join() }
        if (!sound) return null
        val bytes = ByteArray(peaks.size) { level(peaks[it]) }
        file.parentFile?.mkdirs()
        val temp = File(file.path + ".tmp")
        temp.writeBytes(bytes)
        temp.renameTo(file)
        return Waveform(bytes)
    }

    /** Raises each slice's peak with [count] samples starting at [timeUs]. */
    private fun fold(
        peaks: IntArray,
        samples: ShortArray,
        count: Int,
        format: AudioDecoder.Format,
        timeUs: Long,
    ) {
        val channels = format.channels
        var bucket = -1
        var peak = 0
        for (i in 0 until count) {
            val frameUs = (i / channels) * 1_000_000L / format.sampleRate
            val at = ((timeUs + frameUs) / Waveform.BUCKET_US).toInt()
            if (at != bucket) {
                raise(peaks, bucket, peak)
                bucket = at
                peak = 0
            }
            peak = maxOf(peak, abs(samples[i].toInt()))
        }
        raise(peaks, bucket, peak)
    }

    // Neighbouring slices may meet in one bucket; the larger peak wins either way.
    private fun raise(peaks: IntArray, bucket: Int, peak: Int) {
        if (bucket !in peaks.indices) return
        synchronized(peaks) { if (peak > peaks[bucket]) peaks[bucket] = peak }
    }

    private fun level(peak: Int): Byte = (peak * 255 / Short.MAX_VALUE).coerceIn(0, 255).toByte()

    /** Sounds this long or longer decode in [SLICES] slices. */
    private const val SLICE_FROM_US = 10_000_000L
    private const val SLICES = 4
}
