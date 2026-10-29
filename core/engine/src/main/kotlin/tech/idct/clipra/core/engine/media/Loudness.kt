package tech.idct.clipra.core.engine.media

import android.content.Context
import tech.idct.clipra.core.audio.LoudnessMeter

/** Measures a clip's sound on the device (#37), for loudness normalization. */
object Loudness {
    /** Integrated loudness in LUFS and true peak in dBTP. */
    data class Measurement(val lufs: Float, val truePeakDb: Float)

    /**
     * The loudness of [fromUs] to [toUs] of [source] as the export plays it; null when the
     * stretch is silent or has no sound.
     */
    fun measure(context: Context, source: String, fromUs: Long, toUs: Long): Measurement? {
        var meter: LoudnessMeter? = null
        val format =
            AudioDecoder.decode(context, source, fromUs, toUs) { samples, count, format, _ ->
                val current =
                    meter ?: LoudnessMeter(format.sampleRate, format.channels).also { meter = it }
                current.add(samples, count)
            } ?: return null
        val measured = meter ?: return null
        val lufs = measured.integratedLufs() ?: return null
        // A mono sound plays on both channels of the stereo export, which BS.1770 adds up.
        val dualMono = if (format.channels == 1) DUAL_MONO_DB else 0.0
        return Measurement((lufs + dualMono).toFloat(), measured.truePeakDb().toFloat())
    }

    private const val DUAL_MONO_DB = 3.0103
}
