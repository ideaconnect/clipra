package tech.idct.clipra.core.engine.export

import android.media.metrics.LogSessionId
import androidx.annotation.OptIn
import androidx.media3.common.Format
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.Codec

/**
 * [delegate] with the export's [frameRate] on every video format it is asked for (#228); audio
 * passes through unchanged.
 *
 * The export's canvas is an image, and Media3 gives an image's format no frame rate, so
 * [androidx.media3.transformer.DefaultEncoderFactory] told the encoder 30 fps whatever the
 * export's rate was. An encoder whose rate control spends the bitrate per frame by that rate
 * then wrote twice the bitrate at 60 fps (90 MB where the Export screen showed 46 MB), 1.67 times
 * at 50 fps, and less than asked at 24 and 25 fps.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
internal class FrameRateEncoderFactory(
    private val delegate: Codec.EncoderFactory,
    private val frameRate: Float,
) : Codec.EncoderFactory {
    override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForAudioEncoding(format, logSessionId)

    override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec =
        delegate.createForVideoEncoding(atFrameRate(format), logSessionId)

    override fun isVideoFormatSupported(format: Format): Boolean =
        delegate.isVideoFormatSupported(atFrameRate(format))

    override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

    override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()

    private fun atFrameRate(format: Format): Format =
        format.buildUpon().setFrameRate(frameRate).build()
}
