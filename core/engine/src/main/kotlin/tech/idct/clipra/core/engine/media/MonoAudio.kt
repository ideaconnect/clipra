package tech.idct.clipra.core.engine.media

import android.content.Context
import tech.idct.clipra.ml.voice.Resampler
import tech.idct.clipra.ml.voice.SampleSource
import java.io.File

/** Sound as the AI features read it: one channel, at a chosen sample rate. */
object MonoAudio {
    /**
     * Decodes [fromUs] to [toUs] of [source], mixed down to mono, into [target] as WAV at
     * [sampleRate]. Gaps in the track stay silent, so time lines up with the media from
     * [fromUs]. [onProgress] gets 0 to 1. False when the media has no sound.
     */
    fun write(
        context: Context,
        source: String,
        fromUs: Long,
        toUs: Long,
        sampleRate: Int,
        target: File,
        onProgress: (Float) -> Unit = {},
    ): Boolean {
        target.parentFile?.mkdirs()
        val raw = File(target.path + ".raw")
        var writer: WavWriter? = null
        var written = 0L
        var rate = 0
        val format = try {
            AudioDecoder.decode(context, source, fromUs, toUs) { samples, count, format, timeUs ->
                val out = writer ?: WavWriter(raw, format.sampleRate).also {
                    writer = it
                    rate = format.sampleRate
                }
                val expected = (timeUs - fromUs).coerceAtLeast(0) * rate / 1_000_000
                if (expected > written + rate / 100) {
                    val silence = FloatArray((expected - written).toInt())
                    out.write(silence, silence.size)
                    written += silence.size
                }
                val channels = format.channels
                val frames = count / channels
                val mono = FloatArray(frames)
                for (f in 0 until frames) {
                    var sum = 0
                    for (c in 0 until channels) sum += samples[f * channels + c]
                    mono[f] = sum / (32768f * channels)
                }
                out.write(mono, frames)
                written += frames
                if (toUs != Long.MAX_VALUE && toUs > fromUs) {
                    onProgress(((timeUs - fromUs).toFloat() / (toUs - fromUs)).coerceIn(0f, 1f))
                }
            }
        } finally {
            writer?.close()
        }
        if (format == null || writer == null) {
            raw.delete()
            return false
        }
        val part = File(target.path + ".part")
        if (rate == sampleRate) {
            raw.renameTo(part)
        } else {
            WavReader(raw).use { reader ->
                WavWriter(part, sampleRate).use { out ->
                    copy(Resampler(reader, rate, sampleRate), out)
                }
            }
            raw.delete()
        }
        return part.renameTo(target)
    }

    /** Copies all of [source] into [sink]. */
    fun copy(source: SampleSource, sink: WavWriter) {
        val buffer = FloatArray(BLOCK)
        while (true) {
            val n = source.read(buffer, 0, BLOCK)
            if (n < 0) break
            sink.write(buffer, n)
        }
    }

    private const val BLOCK = 8_192
}
