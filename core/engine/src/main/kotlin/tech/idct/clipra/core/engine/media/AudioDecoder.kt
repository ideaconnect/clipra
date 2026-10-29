package tech.idct.clipra.core.engine.media

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import tech.idct.clipra.core.engine.render.MediaSources
import java.nio.ByteOrder

/**
 * Decodes a file's sound track to 16-bit PCM, chunk by chunk: for waveforms (#36) and loudness
 * (#37). Nothing leaves the device.
 */
object AudioDecoder {
    /** The decoded sound's shape. */
    data class Format(val sampleRate: Int, val channels: Int)

    /**
     * Calls [onSamples] with interleaved samples (only the first `count` are valid, the first of
     * them at `timeUs`) from [startUs] to [endUs] of [source]; returns the format, or null when
     * the file has no sound.
     */
    fun decode(
        context: Context,
        source: String,
        startUs: Long = 0,
        endUs: Long = Long.MAX_VALUE,
        onSamples: (samples: ShortArray, count: Int, format: Format, timeUs: Long) -> Unit,
    ): Format? {
        val extractor = MediaSources.open(context, source)
        try {
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(track)
            val trackFormat = extractor.getTrackFormat(track)
            if (startUs >
                0
            ) {
                extractor.seekTo(startUs, android.media.MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            }
            val codec = MediaCodec.createDecoderByType(
                trackFormat.getString(MediaFormat.KEY_MIME)!!,
            )
            var format = Format(
                trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
            )
            try {
                codec.configure(trackFormat, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var buffer = ShortArray(0)
                var inputDone = false
                var ended = false
                while (!ended) {
                    // Keep the codec full: every free input buffer gets a sample...
                    var fed = false
                    while (!inputDone) {
                        val index = codec.dequeueInputBuffer(0)
                        if (index < 0) break
                        fed = true
                        val input = codec.getInputBuffer(index)!!
                        val size = extractor.readSampleData(input, 0)
                        val time = extractor.sampleTime
                        if (size < 0 || time > endUs) {
                            val end = MediaCodec.BUFFER_FLAG_END_OF_STREAM
                            codec.queueInputBuffer(index, 0, 0, 0, end)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(index, 0, size, time, 0)
                            extractor.advance()
                        }
                    }
                    // ...and every ready output is taken; wait only when there was nothing to do.
                    var timeout = if (fed) 0L else TIMEOUT_US
                    while (true) {
                        val index = codec.dequeueOutputBuffer(info, timeout)
                        if (index == MediaCodec.INFO_TRY_AGAIN_LATER) break
                        timeout = 0
                        if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                            val output = codec.outputFormat
                            format = Format(
                                output.getInteger(MediaFormat.KEY_SAMPLE_RATE),
                                output.getInteger(MediaFormat.KEY_CHANNEL_COUNT),
                            )
                        } else if (index >= 0) {
                            if (info.size > 0 && info.presentationTimeUs in startUs..endUs) {
                                val output = codec.getOutputBuffer(index)!!
                                output.position(info.offset)
                                output.limit(info.offset + info.size)
                                val shorts = output.order(ByteOrder.nativeOrder()).asShortBuffer()
                                val count = shorts.remaining()
                                if (buffer.size < count) buffer = ShortArray(count)
                                shorts.get(buffer, 0, count)
                                onSamples(buffer, count, format, info.presentationTimeUs)
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                                ended = true
                                break
                            }
                        }
                    }
                }
            } finally {
                codec.release()
            }
            return format
        } finally {
            extractor.release()
        }
    }

    private const val TIMEOUT_US = 5_000L
}
