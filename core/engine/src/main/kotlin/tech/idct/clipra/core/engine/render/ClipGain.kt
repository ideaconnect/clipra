package tech.idct.clipra.core.engine.render

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.common.util.Util
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * A clip's level (#36): its [volume] (0 to 2) with a fade in over the first [fadeInUs] and a
 * fade out over the last [fadeOutUs] of the clip's [durationUs], plus [normalizeDb] of
 * loudness normalization (#37). With normalization, a limiter keeps peaks under the
 * -1 dBTP ceiling: it pulls the level down at once and lets it back over [RELEASE_US]. The media
 * item may start [offsetUs] into the clip (when a transition gives the earlier clip the
 * overlap). After a seek, the position comes from the stream metadata, so fades stay in place.
 */
@OptIn(UnstableApi::class)
class ClipGain(
    private val volume: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val durationUs: Long,
    private val offsetUs: Long = 0,
    private val normalizeDb: Float = 0f,
) : BaseAudioProcessor() {
    private var frames = 0L
    private val normalize = 10.0.pow(normalizeDb / 20.0).toFloat()
    private val limit = normalizeDb != 0f
    private var envelope = 1f
    private var release = 0f

    /** The gain [timeUs] into the clip. */
    fun gainAt(timeUs: Long): Float {
        val fadingIn = if (fadeInUs > 0) (timeUs.toFloat() / fadeInUs).coerceIn(0f, 1f) else 1f
        val left = durationUs - timeUs
        val fadingOut = if (fadeOutUs > 0) (left.toFloat() / fadeOutUs).coerceIn(0f, 1f) else 1f
        return volume * fadingIn * fadingOut
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        val active = volume != 1f || fadeInUs > 0 || fadeOutUs > 0 || limit
        release = 1f - exp(-1.0 / (RELEASE_US / 1e6 * inputAudioFormat.sampleRate)).toFloat()
        return if (active) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(input.remaining())
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        val frame = FloatArray(format.channelCount)
        while (input.remaining() >= format.bytesPerFrame) {
            var gain =
                gainAt(offsetUs + frames * C.MICROS_PER_SECOND / format.sampleRate) * normalize
            for (c in frame.indices) {
                frame[c] = if (float) input.getFloat() else input.getShort() / 32768f
            }
            if (limit) gain *= limiter(frame, gain)
            for (c in frame.indices) {
                if (float) {
                    output.putFloat(frame[c] * gain)
                } else {
                    val sample = (frame[c] * 32768f * gain).roundToInt()
                    output.putShort(
                        sample.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                    )
                }
            }
            frames++
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        val position = streamMetadata.positionOffsetUs
        frames = Util.durationUsToSampleCount(position, inputAudioFormat.sampleRate)
    }

    override fun onReset() {
        frames = 0
        envelope = 1f
    }

    /** The limiter's gain for this frame: down at once to the ceiling, back up slowly. */
    private fun limiter(frame: FloatArray, gain: Float): Float {
        var peak = 0f
        for (sample in frame) peak = maxOf(peak, abs(sample * gain))
        val needed = if (peak > CEILING) CEILING / peak else 1f
        envelope = if (needed < envelope) needed else envelope + (1f - envelope) * release
        return envelope
    }

    private companion object {
        // Samples stay half a decibel under -1 dBTP, room for peaks between them.
        val CEILING = 10.0.pow(-1.5 / 20).toFloat()
        const val RELEASE_US = 80_000L
    }
}
