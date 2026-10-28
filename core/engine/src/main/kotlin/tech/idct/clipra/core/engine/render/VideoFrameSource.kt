package tech.idct.clipra.core.engine.render

import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ConditionVariable
import android.os.Handler
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * Decodes one video file into an external GL texture, frame by frame on demand. [frameAt] moves
 * the decoder to the last frame at or before a time and makes it current in [textureId]. Create,
 * use and release it on the GL thread that owns the texture; [callbackHandler] receives the
 * frame-available callbacks on another thread.
 */
@OptIn(UnstableApi::class)
internal class VideoFrameSource(path: String, callbackHandler: Handler) {
    val textureId: Int = GlUtil.createExternalTexture()

    /** Maps unit quad coordinates to the texture, from [SurfaceTexture.getTransformMatrix]. */
    val textureMatrix = FloatArray(16).also { GlUtil.setToIdentity(it) }

    val width: Int
    val height: Int

    private val frameAvailable = ConditionVariable()
    private val surfaceTexture = SurfaceTexture(textureId).apply {
        setOnFrameAvailableListener({ frameAvailable.open() }, callbackHandler)
    }
    private val surface = Surface(surfaceTexture)
    private val extractor = MediaExtractor()
    private val codec: MediaCodec
    private val info = MediaCodec.BufferInfo()

    private var inputDone = false
    private var outputDone = false
    private var pendingIndex = -1
    private var pendingTimeUs = 0L
    private var shownTimeUs = 0L
    private var hasFrame = false
    private var needsSeek = true
    private var inputQueued = false

    init {
        extractor.setDataSource(path)
        val track = (0 until extractor.trackCount).first {
            val mime = extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty()
            mime.startsWith("video/")
        }
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val rotation = format.getIntegerOrDefault(MediaFormat.KEY_ROTATION)
        val rawWidth = format.getInteger(MediaFormat.KEY_WIDTH)
        val rawHeight = format.getInteger(MediaFormat.KEY_HEIGHT)
        width = if (rotation % 180 == 0) rawWidth else rawHeight
        height = if (rotation % 180 == 0) rawHeight else rawWidth
        codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(format, surface, null, 0)
        codec.start()
    }

    /**
     * Shows the last frame at or before [sourceTimeUs] (half a frame of tolerance). Returns false
     * while the source has no frame for that time yet. Seeks when the time goes backwards or
     * jumps more than a second ahead.
     */
    fun frameAt(sourceTimeUs: Long, toleranceUs: Long): Boolean {
        val target = sourceTimeUs + toleranceUs
        val backwards = sourceTimeUs < shownTimeUs - toleranceUs
        val farAhead = sourceTimeUs > shownTimeUs + SEEK_AHEAD_US
        if (needsSeek || (hasFrame && (backwards || farAhead))) seekTo(sourceTimeUs)
        while (true) {
            if (pendingIndex < 0 && !nextOutput()) break
            if (pendingTimeUs > target) break
            // The pending frame is due. Show it unless the next one is due too.
            val index = pendingIndex
            val time = pendingTimeUs
            pendingIndex = -1
            if (nextOutput() && pendingTimeUs <= target) {
                codec.releaseOutputBuffer(index, false)
                continue
            }
            show(index, time)
            break
        }
        return hasFrame
    }

    fun release() {
        if (pendingIndex >= 0) codec.releaseOutputBuffer(pendingIndex, false)
        codec.stop()
        codec.release()
        extractor.release()
        surface.release()
        surfaceTexture.release()
        GlUtil.deleteTexture(textureId)
    }

    private fun seekTo(timeUs: Long) {
        if (pendingIndex >= 0) codec.releaseOutputBuffer(pendingIndex, false)
        pendingIndex = -1
        // Flushing before any input would drop the codec-specific data given at configure().
        if (inputQueued) codec.flush()
        inputQueued = false
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputDone = false
        outputDone = false
        needsSeek = false
        shownTimeUs = timeUs
    }

    private fun show(index: Int, timeUs: Long) {
        frameAvailable.close()
        codec.releaseOutputBuffer(index, true)
        check(frameAvailable.block(FRAME_TIMEOUT_MS)) { "No frame from the decoder at $timeUs us" }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)
        shownTimeUs = timeUs
        hasFrame = true
    }

    /** Dequeues the next decoded frame into [pendingIndex]; false at the end of the stream. */
    private fun nextOutput(): Boolean {
        if (outputDone) return false
        var attempts = 0
        while (attempts++ < MAX_ATTEMPTS) {
            feedInput()
            val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
            if (index >= 0) {
                if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                    codec.releaseOutputBuffer(index, false)
                    outputDone = true
                    return false
                }
                pendingIndex = index
                pendingTimeUs = info.presentationTimeUs
                return true
            }
        }
        error("The decoder produced no frame")
    }

    private fun feedInput() {
        if (inputDone) return
        val index = codec.dequeueInputBuffer(0)
        if (index < 0) return
        val buffer = codec.getInputBuffer(index)!!
        val size = extractor.readSampleData(buffer, 0)
        if (size < 0) {
            codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            inputDone = true
        } else {
            codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
            extractor.advance()
            inputQueued = true
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_ATTEMPTS = 500
        const val FRAME_TIMEOUT_MS = 2_000L
        const val SEEK_AHEAD_US = 1_000_000L
    }
}
