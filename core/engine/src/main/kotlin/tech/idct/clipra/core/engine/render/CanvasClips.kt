package tech.idct.clipra.core.engine.render

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.annotation.VisibleForTesting
import androidx.media3.common.C
import androidx.media3.common.util.MediaFormatUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.muxer.BufferInfo
import androidx.media3.muxer.Mp4Muxer
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * The preview's canvas (#227): a blank H.264 clip at a frame rate, which the preview's
 * composition repeats to the timeline's length ([CompositionFactory]).
 *
 * CompositionPlayer plays a still image at 30 frames a second whatever the item's frame rate
 * (Media3 1.11.1, `SequenceImageRenderer`), so with the image canvas 50 and 60 fps projects
 * previewed at 30, and 24 and 25 fps ones got 30 frames a second. A video plays at its own rate,
 * so the preview's canvas is this clip. The export keeps the image: Transformer's image loader
 * uses the item's frame rate.
 *
 * Going from one item of a sequence to the next holds the picture for a few frames (about
 * 0.2 s on a Galaxy S9 and on the emulator), so the clip is long, [DURATION_US], and most
 * projects never reach its end. Its pictures are written directly ([BlankAvc]), a few bytes
 * each, so it takes no encoder and is made in a moment. One clip per rate, kept in the app's
 * files, where clearing the cache can't take it away from a preview that is playing it.
 */
object CanvasClips {
    /** A canvas clip and its length as the file reports it. */
    data class Clip(val file: File, val durationUs: Long)

    /** Ten minutes. */
    const val DURATION_US = 600_000_000L

    /** The compositor draws at the timeline's size whatever its input's, so the clip is tiny. */
    const val SIDE = BlankAvc.SIDE

    private const val TAG = "CanvasClips"
    private const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** Bumped when the clips change, so older files are not used. */
    private const val VERSION = 1

    private val lock = Any()
    private val makeLock = Any()
    private val clips = HashMap<Int, Clip>()
    private val making = HashMap<Int, MutableList<() -> Unit>>()
    private val failed = HashSet<Int>()
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "clipra-canvas").apply { isDaemon = true }
    }

    /**
     * The clip at [frameRate], or null while it isn't made yet: then it is made in the
     * background and [onReady] runs on that thread once it is. A rate whose clip could not be
     * made returns null and never calls [onReady].
     */
    fun clip(context: Context, frameRate: Int, onReady: () -> Unit): Clip? {
        synchronized(lock) {
            clips[frameRate]?.let { return it }
            if (frameRate in failed) return null
            making[frameRate]?.let {
                it += onReady
                return null
            }
        }
        // A clip made by an earlier run of the app opens in a few milliseconds.
        found(context, frameRate)?.let { found ->
            synchronized(lock) { clips[frameRate] = found }
            return found
        }
        synchronized(lock) {
            clips[frameRate]?.let { return it }
            making[frameRate]?.let {
                it += onReady
                return null
            }
            making[frameRate] = mutableListOf(onReady)
        }
        val app = context.applicationContext
        worker.execute {
            val made = runCatching { make(app, frameRate) }
                .onFailure { Log.w(TAG, "No canvas clip at $frameRate fps", it) }
                .getOrNull()
            val callbacks = synchronized(lock) {
                if (made == null) failed += frameRate
                making.remove(frameRate).orEmpty()
            }
            if (made != null) callbacks.forEach { it() }
        }
        return null
    }

    /** The clip at [frameRate], made now if it doesn't exist yet. Blocks; not on the main thread. */
    fun make(context: Context, frameRate: Int): Clip {
        synchronized(lock) { clips[frameRate]?.let { return it } }
        val clip = synchronized(makeLock) {
            found(context, frameRate) ?: makeFile(file(context, frameRate), frameRate)
        }
        synchronized(lock) {
            clips[frameRate] = clip
            failed -= frameRate
        }
        return clip
    }

    /** Forgets the clip at [frameRate] and deletes its file, so that its next use makes it. */
    @VisibleForTesting
    internal fun forget(context: Context, frameRate: Int) {
        synchronized(makeLock) {
            synchronized(lock) {
                clips -= frameRate
                failed -= frameRate
            }
            file(context, frameRate).delete()
        }
    }

    private fun file(context: Context, frameRate: Int) =
        File(context.noBackupFilesDir, "canvas/blank-${frameRate}fps-v$VERSION.mp4")

    private fun makeFile(file: File, frameRate: Int): Clip {
        file.parentFile?.mkdirs()
        // Written aside and moved in whole, so a clip that is there is complete.
        val partial = File(file.path + ".partial")
        val started = SystemClock.elapsedRealtime()
        try {
            mux(partial, frameRate)
            check(partial.renameTo(file)) { "Could not move $partial to $file" }
        } finally {
            partial.delete()
        }
        val made = checkNotNull(read(file)) { "The canvas clip $file can't be read" }
        Log.i(
            TAG,
            "Made a $frameRate fps canvas clip in ${SystemClock.elapsedRealtime() - started} ms: " +
                "${file.length()} bytes, ${made.durationUs} us",
        )
        return made
    }

    /** The clip a run of the app made before, if it is there and reads. */
    private fun found(context: Context, frameRate: Int): Clip? {
        val file = file(context, frameRate)
        if (!file.exists()) return null
        return read(file) ?: run {
            file.delete()
            null
        }
    }

    private fun read(file: File): Clip? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            if (extractor.trackCount != 1) return null
            val format = extractor.getTrackFormat(0)
            if (format.getString(MediaFormat.KEY_MIME) != MIME) return null
            if (!format.containsKey(MediaFormat.KEY_DURATION)) return null
            val durationUs = format.getLong(MediaFormat.KEY_DURATION)
            if (durationUs <= 0) null else Clip(file, durationUs)
        } catch (e: Exception) {
            Log.w(TAG, "Unreadable canvas clip $file", e)
            null
        } finally {
            extractor.release()
        }
    }

    /**
     * Writes [DURATION_US] of blank pictures at [frameRate], a key frame a second. Media3's
     * muxer, not Android's: MediaMuxer takes about 140 µs a sample on a Galaxy S9, five seconds
     * for the 60 fps clip.
     */
    @OptIn(UnstableApi::class)
    private fun mux(file: File, frameRate: Int) {
        val frames = DURATION_US * frameRate / 1_000_000L
        val format = MediaFormat.createVideoFormat(MIME, SIDE, SIDE).apply {
            setByteBuffer("csd-0", ByteBuffer.wrap(START_CODE + BlankAvc.sps))
            setByteBuffer("csd-1", ByteBuffer.wrap(START_CODE + BlankAvc.pps))
            setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
        }
        // A second's pictures: an IDR, then skipped ones. The groups differ only in the IDR's id.
        // The muxer batches samples and keeps their buffers until it writes them, so each
        // sample gets its own view of a picture that never changes.
        val idrs = Array(2) { ByteBuffer.wrap(START_CODE + BlankAvc.idr(it)) }
        val skipped = Array(frameRate) {
            if (it == 0) null else ByteBuffer.wrap(START_CODE + BlankAvc.skipped(it))
        }
        FileOutputStream(file).use { output ->
            val muxer = Mp4Muxer.Builder(output)
                .setSampleBatchingEnabled(true)
                .setAttemptStreamableOutputEnabled(false)
                .build()
            try {
                val track = muxer.addTrack(MediaFormatUtil.createFormatFromMediaFormat(format))
                for (i in 0 until frames) {
                    val inGroup = (i % frameRate).toInt()
                    val key = inGroup == 0
                    val picture =
                        (if (key) idrs[((i / frameRate) % 2).toInt()] else skipped[inGroup]!!)
                            .duplicate()
                    val flags = if (key) C.BUFFER_FLAG_KEY_FRAME else 0
                    val timeUs = i * 1_000_000L / frameRate
                    muxer.writeSampleData(
                        track,
                        picture,
                        BufferInfo(timeUs, picture.remaining(), flags),
                    )
                }
            } finally {
                muxer.close()
            }
        }
    }
}
