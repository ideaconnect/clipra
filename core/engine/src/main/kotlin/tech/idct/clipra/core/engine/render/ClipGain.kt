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
import kotlin.math.roundToInt

/**
 * A clip's level (#36): its [volume] (0 to 2) with a fade in over the first [fadeInUs] and a
 * fade out over the last [fadeOutUs] of the clip's [durationUs]. The media item may start
 * [offsetUs] into the clip (when a transition gives the earlier clip the overlap). After a
 * seek, the position comes from the stream metadata, so fades stay in place.
 */
@OptIn(UnstableApi::class)
class ClipGain(
    private val volume: Float,
    private val fadeInUs: Long,
    private val fadeOutUs: Long,
    private val durationUs: Long,
    private val offsetUs: Long = 0,
) : BaseAudioProcessor() {
    private var frames = 0L

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
        val active = volume != 1f || fadeInUs > 0 || fadeOutUs > 0
        return if (active) inputAudioFormat else AudioFormat.NOT_SET
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val format = inputAudioFormat
        val input = inputBuffer.order(ByteOrder.nativeOrder())
        val output = replaceOutputBuffer(input.remaining())
        val float = format.encoding == C.ENCODING_PCM_FLOAT
        while (input.remaining() >= format.bytesPerFrame) {
            val gain = gainAt(offsetUs + frames * C.MICROS_PER_SECOND / format.sampleRate)
            repeat(format.channelCount) {
                if (float) {
                    output.putFloat(input.getFloat() * gain)
                } else {
                    val sample = (input.getShort() * gain).roundToInt()
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
    }
}
