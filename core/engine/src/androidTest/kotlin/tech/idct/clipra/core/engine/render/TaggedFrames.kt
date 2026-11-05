package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Frames of a video file in the colours its tags give (#201). The decoder hands over its YUV
 * planes, and this converts them with the matrix and range the file is tagged with. Readers
 * that convert by themselves, such as `MediaMetadataRetriever` or a GPU's external textures,
 * may pick another matrix: the Galaxy S9's retriever reads a file tagged BT.709 as BT.601, so
 * a check through it can pass on wrong colours.
 */
object TaggedFrames {
    /** A decoded frame at [timeUs], upright, converted with [tags]. */
    class Frame(val timeUs: Long, val bitmap: Bitmap, val tags: ColourTags)

    /**
     * The colour tags of a video: [standard] and [range] as `MediaFormat` names them, and
     * [source], where they were found (the file's container or its video stream).
     */
    data class ColourTags(val standard: Int, val range: Int, val source: String) {
        override fun toString(): String {
            val matrix = when (standard) {
                MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
                MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601 (525)"
                MediaFormat.COLOR_STANDARD_BT601_PAL -> "BT.601 (625)"
                MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
                else -> "standard $standard"
            }
            val levels = if (range == MediaFormat.COLOR_RANGE_FULL) "full" else "limited"
            return "$matrix, $levels range, from the $source"
        }
    }

    /**
     * The frames of the video at [path] closest to each of [timesUs], keyed by the requested
     * time. Fails when neither the container nor the stream says which colours it holds.
     */
    fun read(path: String, timesUs: List<Long>): Map<Long, Frame> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(path)
            val track = (0 until extractor.trackCount).first {
                val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
                mime.startsWith("video/")
            }
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            val wanted = closestSamples(extractor, timesUs)
            extractor.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            return decode(extractor, format, wanted)
        } finally {
            extractor.release()
        }
    }

    /** For each sample time, the requested times it is the closest sample to. */
    private fun closestSamples(
        extractor: MediaExtractor,
        timesUs: List<Long>,
    ): Map<Long, List<Long>> {
        val samples = ArrayList<Long>()
        while (extractor.sampleTime >= 0) {
            samples += extractor.sampleTime
            extractor.advance()
        }
        check(samples.isNotEmpty()) { "The video track has no samples" }
        return timesUs.distinct().groupBy { time -> samples.minBy { abs(it - time) } }
    }

    private fun decode(
        extractor: MediaExtractor,
        format: MediaFormat,
        wanted: Map<Long, List<Long>>,
    ): Map<Long, Frame> {
        val rotation = format.intOrNull(MediaFormat.KEY_ROTATION) ?: 0
        val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        val frames = HashMap<Long, Frame>()
        val requested = wanted.values.sumOf { it.size }
        try {
            format.setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            val deadline = SystemClock.elapsedRealtime() + TIMEOUT_MS
            while (frames.size < requested) {
                check(SystemClock.elapsedRealtime() < deadline) { "Decoding took too long" }
                if (!inputDone) inputDone = feed(codec, extractor)
                val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
                if (index < 0) continue
                val times = wanted[info.presentationTimeUs]
                if (times != null && info.size > 0) {
                    val tags = tags(format, codec.getOutputFormat(index))
                    val bitmap = codec.getOutputImage(index)!!.use { toBitmap(it, tags) }
                    val upright = upright(bitmap, rotation)
                    for (time in times) frames[time] = Frame(info.presentationTimeUs, upright, tags)
                }
                codec.releaseOutputBuffer(index, false)
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
            }
        } finally {
            codec.stop()
            codec.release()
        }
        check(frames.size == requested) { "Decoded ${frames.size} of $requested frames" }
        return frames
    }

    /** Queues the next sample; true once the end of the stream is queued. */
    private fun feed(codec: MediaCodec, extractor: MediaExtractor): Boolean {
        val index = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
        if (index < 0) return false
        val size = extractor.readSampleData(codec.getInputBuffer(index)!!, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            return true
        }
        codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
        extractor.advance()
        return false
    }

    /**
     * The container's tags (an MP4 `colr` box), else the stream's (the H.264 or HEVC video
     * usability information), as the decoder reports them.
     */
    private fun tags(container: MediaFormat, stream: MediaFormat): ColourTags {
        val (format, source) = listOf(container to "container", stream to "stream")
            .firstOrNull { (format, _) -> format.intOrNull(MediaFormat.KEY_COLOR_STANDARD) != null }
            ?: error("The video has no colour tags")
        val range = format.intOrNull(MediaFormat.KEY_COLOR_RANGE)
        return ColourTags(
            standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD),
            range = range ?: MediaFormat.COLOR_RANGE_LIMITED,
            source = source,
        )
    }

    /** Converts a YUV 4:2:0 frame with the matrix and range of [tags]. */
    private fun toBitmap(image: Image, tags: ColourTags): Bitmap {
        val levels = Levels(tags)
        val crop = image.cropRect
        val width = crop.width()
        val pixels = IntArray(width * crop.height())
        for (row in 0 until crop.height()) {
            levels.convertRow(image.planes, crop.left, crop.top + row, pixels, row * width, width)
        }
        return Bitmap.createBitmap(pixels, width, crop.height(), Bitmap.Config.ARGB_8888)
    }

    /**
     * What each 8-bit Y, Cb and Cr value adds to R, G and B under the matrix and range of
     * [tags], so a pixel takes table lookups and no arithmetic on its own. Written out per
     * pixel, the same sums came out as NaN on the Galaxy S9 (Android 10).
     */
    private class Levels(tags: ColourTags) {
        private val luma = DoubleArray(256)
        private val redFromCr = DoubleArray(256)
        private val greenFromCb = DoubleArray(256)
        private val greenFromCr = DoubleArray(256)
        private val blueFromCb = DoubleArray(256)

        init {
            val (kr, kb) = WEIGHTS[tags.standard] ?: error("No matrix for $tags")
            val full = tags.range == MediaFormat.COLOR_RANGE_FULL
            val lumaOffset = if (full) 0.0 else 16.0
            val lumaScale = if (full) 255.0 else 219.0
            val chromaScale = if (full) 255.0 else 224.0
            val kg = 1 - kr - kb
            for (value in 0..255) {
                val chroma = (value - 128) / chromaScale
                luma[value] = (value - lumaOffset) / lumaScale
                redFromCr[value] = 2 * (1 - kr) * chroma
                blueFromCb[value] = 2 * (1 - kb) * chroma
                greenFromCr[value] = -kr * redFromCr[value] / kg
                greenFromCb[value] = -kb * blueFromCb[value] / kg
            }
        }

        /** Converts [width] pixels of row [y], from column [left], into [out] at [offset]. */
        fun convertRow(
            planes: Array<Image.Plane>,
            left: Int,
            y: Int,
            out: IntArray,
            offset: Int,
            width: Int,
        ) {
            val (lumaPlane, cbPlane, crPlane) = planes
            val lumaRow = y * lumaPlane.rowStride
            val cbRow = (y / 2) * cbPlane.rowStride
            val crRow = (y / 2) * crPlane.rowStride
            for (column in 0 until width) {
                val x = left + column
                val l = byte(lumaPlane, lumaRow + x * lumaPlane.pixelStride)
                val cb = byte(cbPlane, cbRow + (x / 2) * cbPlane.pixelStride)
                val cr = byte(crPlane, crRow + (x / 2) * crPlane.pixelStride)
                val base = luma[l]
                out[offset + column] = Color.rgb(
                    level(base + redFromCr[cr]),
                    level(base + greenFromCb[cb] + greenFromCr[cr]),
                    level(base + blueFromCb[cb]),
                )
            }
        }

        private fun byte(plane: Image.Plane, index: Int) = plane.buffer.get(index).toInt() and 0xFF
    }

    private fun level(value: Double) = (value * 255).roundToInt().coerceIn(0, 255)

    /** Turns a frame the way the file's rotation says players show it. */
    private fun upright(bitmap: Bitmap, rotation: Int): Bitmap {
        if (rotation % 360 == 0) return bitmap
        val turn = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, turn, false)
    }

    private fun MediaFormat.intOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    /** The luma weights (Kr, Kb) of each matrix. */
    private val WEIGHTS = mapOf(
        MediaFormat.COLOR_STANDARD_BT709 to (0.2126 to 0.0722),
        MediaFormat.COLOR_STANDARD_BT601_NTSC to (0.299 to 0.114),
        MediaFormat.COLOR_STANDARD_BT601_PAL to (0.299 to 0.114),
        MediaFormat.COLOR_STANDARD_BT2020 to (0.2627 to 0.0593),
    )

    private const val DEQUEUE_TIMEOUT_US = 10_000L
    private const val TIMEOUT_MS = 60_000L
}
