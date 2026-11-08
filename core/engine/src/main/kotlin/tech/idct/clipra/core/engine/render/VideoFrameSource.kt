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
 * A copy of a stretch of media laid out back to front (`ReversedVideos`, #229) is read with
 * [mirrorUs], the media time the copy's start stands for: the copy's frame at t holds the media's
 * frame at [mirrorUs] - t. [frameAt] and [frameTimeUs] still speak in the media's time, and the
 * copy plays forwards while the media plays backwards.
 *
 * The decoder runs ahead on a thread of its own (#243): it reads the file and keeps up to
 * [LOOKAHEAD] decoded frames ready, so [frameAt] seldom waits for it. [sendNext] then sends the
 * frame after the one shown on to the texture's queue, so it has arrived when the next canvas
 * needs it. Feeding each decoder and waiting for each frame in turn, one layer after another on
 * the GL thread, took a Galaxy S9 at its idle clocks about 25 ms of every canvas of three
 * layers, most of a frame.
 */
@OptIn(UnstableApi::class)
internal class VideoFrameSource(
    context: Context,
    path: String,
    callbackHandler: Handler,
    private val mirrorUs: Long? = null,
) {
    val textureId: Int = GlUtil.createExternalTexture()

    /** Maps unit quad coordinates to the texture, from [SurfaceTexture.getTransformMatrix]. */
    val textureMatrix = FloatArray(16).also { GlUtil.setToIdentity(it) }

    val width: Int
    val height: Int

    /** The media time of the frame [textureId] shows. */
    val frameTimeUs: Long get() = mirrorUs?.let { it - shownTimeUs } ?: shownTimeUs

    /** How many frames [textureId] has shown; it changes with each new frame. */
    var shownFrames = 0L
        private set

    /**
     * How many frames the decoder has put out, shown or not: what reading the file cost. It
     * counts the frames decoded ahead too, up to [LOOKAHEAD] more than were asked for.
     */
    @Volatile
    var decodedFrames = 0L
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

    /** The time of the key frame the last seek reached, or [NONE] at the end of the file. */
    private var seekKeyUs = NONE
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

    /** Reading a copy back to front: the file time of the frame decoded before the one shown. */
    private var beforeShownUs = Long.MIN_VALUE

    /** The file time of the last frame taken from the decoder, shown or dropped. */
    private var lastDecodedUs = Long.MIN_VALUE

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
        // In a copy laid out back to front, the media's last frame at or before a time is the
        // copy's first frame at or after the mirrored time. The copy's frame times are whole
        // microseconds of its own frame rate and its muxer's time scale, a few off the media's,
        // which would miss a frame that the media has exactly at the time.
        if (mirrorUs != null) {
            val fileTimeUs = mirrorUs - sourceTimeUs - toleranceUs - MIRROR_ROUNDING_US
            return frameFrom(fileTimeUs, toleranceUs)
        }
        val target = sourceTimeUs + toleranceUs
        nextTargetUs = target + 2 * toleranceUs
        val backwards = sourceTimeUs < shownTimeUs - toleranceUs
        val farAhead = sourceTimeUs > shownTimeUs + SEEK_AHEAD_US
        if (needsSeek || (hasFrame && (backwards || farAhead))) seekTo(sourceTimeUs)
        // The frame sent on is the next one. Once due it is shown, and a later one that is due
        // as well (the canvas skipped a frame) follows it.
        if (sentTimeUs != NONE) {
            if (sentTimeUs > target) return hasFrame
            latchSent()
        }
        while (true) {
            val next = awaitDecoded(0) ?: break
            if (next.timeUs > target) break
            // The next frame is due. Show it unless the one after it is due too.
            val after = awaitDecoded(1)
            if (after != null && after.timeUs <= target) {
                drop(next)
                continue
            }
            show(next)
            break
        }
        return hasFrame
    }

    /**
     * Sends the decoded frame after the one shown on to the texture's queue, so that it has
     * arrived when the next canvas shows it. Call it once the shown frame has been read: the
     * texture keeps showing that one until the next [frameAt]. Nothing is sent when the next
     * canvas would pass the next frame over: when the frame after it is due by then too (a
     * video at a higher frame rate than the project's), or, reading a copy back to front, when
     * the next frame comes before the next canvas's time and another follows it.
     */
    fun sendNext() {
        if (sentTimeUs != NONE || !hasFrame || nextTargetUs == NONE) return
        val next: Decoded
        synchronized(lock) {
            next = decoded.firstOrNull() ?: return
            val after = decoded.getOrNull(1)
            val passedOver = if (mirrorUs != null) {
                after != null && next.timeUs < nextTargetUs
            } else {
                after != null && after.timeUs <= nextTargetUs
            }
            if (passedOver) return
            decoded.removeFirst()
            lock.notifyAll()
        }
        frameAvailable.close()
        codec.releaseOutputBuffer(next.index, true)
        sentTimeUs = next.timeUs
    }

    /**
     * Moves to the last key frame at or before [timeUs] (the first one when none is), for
     * reading frame by frame with [nextFrameUs]. Returns that key frame's time.
     */
    fun seekToKeyFrame(timeUs: Long): Long {
        seekTo(timeUs)
        val keyUs = synchronized(lock) { seekKeyUs }
        return keyUs.takeIf { it >= 0 } ?: timeUs
    }

    /**
     * Decodes the next frame and returns its time without showing it, or null at the end of
     * the file. [showNext] then shows it and [skipNext] drops it; until one of them is called,
     * this returns the same frame.
     */
    fun nextFrameUs(): Long? {
        check(sentTimeUs == NONE) { "A frame was sent on: read on with frameAt" }
        // Read from the start when nothing has moved the decoder yet.
        if (needsSeek) seekTo(0)
        return awaitDecoded(0)?.timeUs
    }

    /** Shows the frame [nextFrameUs] decoded in [textureId]. */
    fun showNext() {
        show(checkNotNull(firstDecoded()) { "No frame is decoded" })
    }

    /** Drops the frame [nextFrameUs] decoded without drawing it. */
    fun skipNext() {
        drop(checkNotNull(firstDecoded()) { "No frame is decoded" })
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

    /**
     * Shows the first frame at or after [timeUs] in the file, or its last frame when it ends
     * before: reading a copy laid out back to front. Seeks when the time goes back to the frame
     * before the one shown or jumps more than a second ahead.
     */
    private fun frameFrom(timeUs: Long, toleranceUs: Long): Boolean {
        nextTargetUs = timeUs + 2 * toleranceUs
        // Still the first frame at or after the time.
        if (hasFrame && !needsSeek && timeUs > beforeShownUs && timeUs <= shownTimeUs) return true
        val backwards = timeUs <= beforeShownUs
        val farAhead = timeUs > shownTimeUs + SEEK_AHEAD_US
        if (needsSeek || (hasFrame && (backwards || farAhead))) seekTo(timeUs)
        // The frame sent on is the one after the frame shown: the first to look at. It holds
        // when it comes at or after the time.
        if (sentTimeUs != NONE) {
            val sent = sentTimeUs
            latchSent()
            if (sent >= timeUs) return true
        }
        while (true) {
            val next = awaitDecoded(0) ?: break
            // An earlier frame is passed over, unless the file ends after it.
            if (next.timeUs < timeUs && awaitDecoded(1) != null) {
                drop(next)
                continue
            }
            show(next)
            break
        }
        return hasFrame
    }

    /** Shows [frame], the first decoded one, in [textureId]. */
    private fun show(frame: Decoded) {
        take(frame)
        frameAvailable.close()
        codec.releaseOutputBuffer(frame.index, true)
        latch(frame.timeUs)
    }

    /** Drops [frame], the first decoded one, without drawing it. */
    private fun drop(frame: Decoded) {
        take(frame)
        codec.releaseOutputBuffer(frame.index, false)
        lastDecodedUs = frame.timeUs
    }

    /** Makes the frame [sendNext] sent current. */
    private fun latchSent() {
        val timeUs = sentTimeUs
        sentTimeUs = NONE
        latch(timeUs)
    }

    /** Makes the frame sent to the texture, at [timeUs], current once it has arrived. */
    private fun latch(timeUs: Long) {
        check(frameAvailable.block(FRAME_TIMEOUT_MS)) { "No frame from the decoder at $timeUs us" }
        surfaceTexture.updateTexImage()
        surfaceTexture.getTransformMatrix(textureMatrix)
        shownTimeUs = timeUs
        hasFrame = true
        shownFrames++
        beforeShownUs = lastDecodedUs
        lastDecodedUs = timeUs
    }

    private fun seekTo(timeUs: Long) {
        // A frame on its way to the texture would come out after the seek: take it now.
        if (sentTimeUs != NONE) latchSent()
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
        lastDecodedUs = Long.MIN_VALUE
    }

    /** The first decoded frame, if the decoder has one ready. */
    private fun firstDecoded(): Decoded? = synchronized(lock) { decoded.firstOrNull() }

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
        val keyUs = extractor.sampleTime.takeIf { it >= 0 } ?: NONE
        inputDone = false
        synchronized(lock) {
            seekKeyUs = keyUs
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
                decodedFrames++
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

        /** Far more than timestamps round by, far less than a frame lasts at 240 fps. */
        const val MIRROR_ROUNDING_US = 200L

        /**
         * Decoded frames kept ready. The GL thread never waits for more than three of the
         * codec's output buffers at once (the one shown, then the one sent on or the next one,
         * and one more), as many as decoding frame by frame on the GL thread held.
         */
        const val LOOKAHEAD = 2
        const val NONE = Long.MIN_VALUE
    }
}
