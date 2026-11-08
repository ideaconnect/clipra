package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.ConditionVariable
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi

/**
 * Decodes one video file into an external GL texture, frame by frame on demand. [frameAt] moves
 * the decoder to the last frame at or before a time and makes it current in [textureId]. Create,
 * use and release it on the GL thread that owns the texture; [callbackHandler] receives the
 * frame-available callbacks on another thread.
 *
 * The decoder runs ahead on a thread of its own (#243): it reads the file and keeps up to
 * [LOOKAHEAD] decoded frames ready, so [frameAt] seldom waits for it. [sendNext] then sends the
 * frame after the one shown on to the texture's queue, so it has arrived when the next canvas
 * needs it. Feeding each decoder and waiting for each frame in turn, one layer after another on
 * the GL thread, took a Galaxy S9 at its idle clocks about 25 ms of every canvas of three
 * layers, most of a frame.
 */
@OptIn(UnstableApi::class)
internal class VideoFrameSource(context: Context, path: String, callbackHandler: Handler) {
    val textureId: Int = GlUtil.createExternalTexture()

    /** Maps unit quad coordinates to the texture, from [SurfaceTexture.getTransformMatrix]. */
    val textureMatrix = FloatArray(16).also { GlUtil.setToIdentity(it) }

    val width: Int
    val height: Int

    /** The media time of the frame [textureId] shows. */
    val frameTimeUs: Long get() = shownTimeUs

    /** How many frames [textureId] has shown; it changes with each new frame. */
    var shownFrames = 0L
        private set

    private val frameAvailable = ConditionVariable()
    private val surfaceTexture = SurfaceTexture(textureId).apply {
        setOnFrameAvailableListener({ frameAvailable.open() }, callbackHandler)
    }
    private val surface = Surface(surfaceTexture)
    private val extractor = MediaSources.open(context, path)
    private val codec: MediaCodec

    // Shared by the GL thread and the decoder thread, under [lock].
    private val lock = Object()

    /** Decoded frames not yet shown or dropped, in order: their output buffers and times. */
    private val decoded = ArrayDeque<Decoded>()
    private var endOfStream = false
    private var seekRequestUs = NONE
    private var released = false
    private var failure: Throwable? = null

    private val decoder = Thread({ decode() }, "clipra-decoder")

    // The GL thread's own.
    private var shownTimeUs = 0L
    private var hasFrame = false
    private var needsSeek = true

    /** The time the next [frameAt] probably asks for: a frame after the last one. */
    private var nextTargetUs = NONE

    /** The media time of the frame on its way to the texture, not made current yet, or [NONE]. */
    private var sentTimeUs = NONE

    private class Decoded(val index: Int, val timeUs: Long)

    init {
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
        // The decoder thread starts the codec at the first seek. From here on it owns the
        // extractor and every call that feeds the codec or takes frames out of it.
        decoder.start()
    }

    /**
     * Shows the last frame at or before [sourceTimeUs] (half a frame of tolerance). Returns false
     * while the source has no frame for that time yet. Seeks when the time goes backwards or
     * jumps more than a second ahead.
     */
    fun frameAt(sourceTimeUs: Long, toleranceUs: Long): Boolean {
        val target = sourceTimeUs + toleranceUs
        nextTargetUs = target + 2 * toleranceUs
        val backwards = sourceTimeUs < shownTimeUs - toleranceUs
        val farAhead = sourceTimeUs > shownTimeUs + SEEK_AHEAD_US
        if (needsSeek || (hasFrame && (backwards || farAhead))) seekTo(sourceTimeUs)
        // The frame sent on is the next one. Once due it is shown, and a later one that is due
        // as well (the canvas skipped a frame) follows it.
        if (sentTimeUs != NONE) {
            if (sentTimeUs > target) return hasFrame
            latch(sentTimeUs)
            sentTimeUs = NONE
        }
        while (true) {
            val next = awaitDecoded(0) ?: break
            if (next.timeUs > target) break
            // The next frame is due. Show it unless the one after it is due too.
            val after = awaitDecoded(1)
            take(next)
            if (after != null && after.timeUs <= target) {
                codec.releaseOutputBuffer(next.index, false)
                continue
            }
            frameAvailable.close()
            codec.releaseOutputBuffer(next.index, true)
            latch(next.timeUs)
            break
        }
        return hasFrame
    }

    /**
     * Sends the decoded frame after the one shown on to the texture's queue, so that it has
     * arrived when the next canvas shows it. Call it once the shown frame has been read: the
     * texture keeps showing that one until the next [frameAt]. Nothing is sent when the frame
     * after the next one would be due by the next canvas too (a video at a higher frame rate
     * than the project's), since that canvas skips the next one.
     */
    fun sendNext() {
        if (sentTimeUs != NONE || !hasFrame || nextTargetUs == NONE) return
        val next: Decoded
        synchronized(lock) {
            next = decoded.firstOrNull() ?: return
            val after = decoded.getOrNull(1)
            if (after != null && after.timeUs <= nextTargetUs) return
            decoded.removeFirst()
            lock.notifyAll()
        }
        frameAvailable.close()
        codec.releaseOutputBuffer(next.index, true)
        sentTimeUs = next.timeUs
    }

    fun release() {
        synchronized(lock) {
            released = true
            lock.notifyAll()
        }
        decoder.join()
        // The decoder thread has ended, so its state can be read here.
        if (started) codec.stop()
        codec.release()
        extractor.release()
        surface.release()
        surfaceTexture.release()
        GlUtil.deleteTexture(textureId)
    }

    /** Makes the frame sent to the texture, at [timeUs], current once it has arrived. */
    private fun latch(timeUs: Long) {
        check(frameAvailable.block(FRAME_TIMEOUT_MS)) { "No frame from the decoder at $timeUs us" }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)
        shownTimeUs = timeUs
        hasFrame = true
        shownFrames++
    }

    private fun seekTo(timeUs: Long) {
        // A frame on its way to the texture would come out after the seek: take it now.
        if (sentTimeUs != NONE) {
            latch(sentTimeUs)
            sentTimeUs = NONE
        }
        synchronized(lock) {
            seekRequestUs = timeUs
            lock.notifyAll()
            while (seekRequestUs != NONE) {
                failure?.let { throw IllegalStateException("The decoder failed", it) }
                lock.wait()
            }
        }
        needsSeek = false
        shownTimeUs = timeUs
    }

    /**
     * The decoded frame at [position] in the queue, once the decoder has it; null at the end of
     * the stream.
     */
    private fun awaitDecoded(position: Int): Decoded? {
        val deadline = SystemClock.uptimeMillis() + DECODE_TIMEOUT_MS
        synchronized(lock) {
            while (true) {
                decoded.getOrNull(position)?.let { return it }
                failure?.let { throw IllegalStateException("The decoder failed", it) }
                if (endOfStream) return null
                val left = deadline - SystemClock.uptimeMillis()
                check(left > 0) { "The decoder produced no frame" }
                lock.wait(left)
            }
        }
    }

    /** Takes [frame], the first decoded one, out of the queue for the GL thread to release. */
    private fun take(frame: Decoded) {
        synchronized(lock) {
            check(decoded.removeFirst() === frame)
            lock.notifyAll()
        }
    }

    // The decoder thread's own.
    private var started = false
    private var inputDone = false
    private var inputQueued = false
    private val info = MediaCodec.BufferInfo()

    /** Feeds the codec and queues its frames, up to [LOOKAHEAD], until [release]. */
    private fun decode() {
        try {
            while (true) {
                val seekUs = synchronized(lock) {
                    while (!released && seekRequestUs == NONE &&
                        (!started || endOfStream || decoded.size >= LOOKAHEAD)
                    ) {
                        lock.wait()
                    }
                    if (released) return
                    seekRequestUs
                }
                if (seekUs != NONE) {
                    seek(seekUs)
                } else {
                    feedInput()
                    takeOutput()
                }
            }
        } catch (e: Throwable) {
            synchronized(lock) {
                failure = e
                lock.notifyAll()
            }
        }
    }

    private fun seek(timeUs: Long) {
        // The GL thread waits meanwhile; a flush gives the frames held back to the codec.
        synchronized(lock) {
            decoded.clear()
            endOfStream = false
        }
        if (!started) {
            codec.start()
            started = true
        } else if (inputQueued) {
            // Flushing before any input would drop the codec-specific data given at configure().
            codec.flush()
        }
        inputQueued = false
        extractor.seekTo(timeUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        inputDone = false
        synchronized(lock) {
            seekRequestUs = NONE
            lock.notifyAll()
        }
    }

    private fun feedInput() {
        while (!inputDone) {
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
    }

    private fun takeOutput() {
        val index = codec.dequeueOutputBuffer(info, DEQUEUE_TIMEOUT_US)
        if (index < 0) return
        val endsStream = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        if (endsStream) codec.releaseOutputBuffer(index, false)
        synchronized(lock) {
            if (endsStream) {
                endOfStream = true
            } else {
                decoded.addLast(Decoded(index, info.presentationTimeUs))
            }
            lock.notifyAll()
        }
    }

    private fun MediaFormat.getIntegerOrDefault(key: String): Int =
        if (containsKey(key)) getInteger(key) else 0

    private companion object {
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val DECODE_TIMEOUT_MS = 5_000L
        const val FRAME_TIMEOUT_MS = 2_000L
        const val SEEK_AHEAD_US = 1_000_000L

        /**
         * Decoded frames kept ready. The GL thread never waits for more than three of the
         * codec's output buffers at once (the one shown, then the one sent on or the next one,
         * and one more), as many as decoding frame by frame on the GL thread held.
         */
        const val LOOKAHEAD = 2
        const val NONE = Long.MIN_VALUE
    }
}
