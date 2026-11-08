package tech.idct.clipra.core.engine.render

import android.content.Context
import android.opengl.GLES20
import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Plays a video file back to front (#22) without decoding every frame again from its key frame
 * (#229). A decoder only runs forwards from a key frame, and [VideoFrameSource] asked for each
 * earlier frame seeks back to one: a reversed clip cost half a group of pictures for every frame
 * shown, about 30 frames from a 60 fps proxy with a key frame a second, and its preview ran at
 * 6 fps on a Galaxy S9, its export ten times slower than forwards.
 *
 * This one decodes from the key frame before the time asked for up to that time once, keeps the
 * last [slots] frames on the way in textures of its own, and shows earlier times from them until
 * they run out; then it decodes the stretch before them the same way. A group of pictures that
 * fits in the slots is decoded once; a longer one in a few passes. The frame shown for a time is
 * the one [VideoFrameSource.frameAt] shows: the last at or before it, with the tolerance given.
 *
 * Frames are kept upright, as the compositor copies them: at most [maxSide] on their long side,
 * and smaller where [budgetBytes] would otherwise hold fewer than [MIN_SLOTS] of them, though not
 * below [frameSide], the long side of the frame they are drawn in. Create, use and release it on
 * the GL thread.
 */
@OptIn(UnstableApi::class)
internal class ReversedFrameSource(
    context: Context,
    path: String,
    callbackHandler: Handler,
    maxSide: Int,
    frameSide: Int,
    budgetBytes: Long = BUDGET_BYTES,
) {
    private val video = VideoFrameSource(context, path, callbackHandler)

    val width: Int get() = video.width
    val height: Int get() = video.height

    /** How many frames the decoder has put out: what playing the file back to front cost. */
    val decodedFrames: Long get() = video.decodedFrames

    /** The frame shown: a 2D texture, upright as the compositor's copies are. */
    val textureId: Int get() = shown?.texture ?: 0

    /** The media time of the frame shown. */
    val frameTimeUs: Long get() = shown?.timeUs ?: 0

    /** The size of each kept frame, and how many are kept at most. */
    val slotWidth: Int
    val slotHeight: Int
    val slots: Int

    private val program = GlProgram(Shaders.VERTEX, Shaders.CLIP_EXTERNAL)

    /** The kept frames in time order, decoded one after the other with nothing between. */
    private val kept = ArrayDeque<Slot>()
    private val free = ArrayList<Slot>()
    private var shown: Slot? = null

    /** The frame decoded after the last kept one: from there on the slots don't know. */
    private var nextUs = Long.MIN_VALUE

    /** The first kept frame is the file's first: it holds for earlier times too. */
    private var holdsEarlier = false

    init {
        val longest = maxOf(width, height).toFloat()
        val pixels = width.toDouble() * height
        val full = minOf(1f, maxSide / longest)
        val own = minOf(1f, frameSide / longest)
        val fitting = sqrt(budgetBytes / MIN_SLOTS / BYTES_PER_PIXEL / pixels).toFloat()
        val scale = minOf(full, maxOf(own, fitting))
        slotWidth = maxOf(2, (width * scale).roundToInt())
        slotHeight = maxOf(2, (height * scale).roundToInt())
        val bytes = slotWidth.toLong() * slotHeight * BYTES_PER_PIXEL
        slots = (budgetBytes / bytes).toInt().coerceIn(2, MAX_SLOTS)
    }

    /**
     * Shows the last frame at or before [sourceTimeUs] (with [toleranceUs]); false while the
     * file has no frame at all.
     */
    fun frameAt(sourceTimeUs: Long, toleranceUs: Long): Boolean {
        val target = sourceTimeUs + toleranceUs
        if (!covers(target)) fill(target)
        shown = kept.lastOrNull { it.timeUs <= target } ?: kept.firstOrNull()
        return shown != null
    }

    fun release() {
        video.release()
        (kept + free).forEach { it.delete() }
        kept.clear()
        free.clear()
        shown = null
        program.delete()
    }

    private fun covers(target: Long): Boolean {
        val first = kept.firstOrNull() ?: return false
        return target < nextUs && (target >= first.timeUs || holdsEarlier)
    }

    /**
     * Decodes from the key frame at or before [target] up to the first frame after it, and
     * keeps the last [slots] frames at or before it.
     */
    private fun fill(target: Long) {
        free += kept
        kept.clear()
        holdsEarlier = false
        nextUs = Long.MAX_VALUE
        val keyUs = video.seekToKeyFrame(target)
        while (true) {
            // At the end of the file the last frame kept holds.
            val time = video.nextFrameUs() ?: break
            // Pictures that lead a key frame in an open group of pictures come out before it
            // after a seek, without the pictures they refer to; the group before holds them.
            if (time < keyUs) {
                video.skipNext()
                continue
            }
            if (time > target) {
                if (kept.isNotEmpty()) {
                    nextUs = time
                    break
                }
                // Nothing of the file comes at or before the time: its first frame holds.
                holdsEarlier = true
            }
            keep(time)
        }
    }

    private fun keep(timeUs: Long) {
        val slot = when {
            kept.size >= slots -> kept.removeFirst()
            free.isNotEmpty() -> free.removeAt(free.lastIndex)
            else -> Slot.create(slotWidth, slotHeight)
        }
        video.showNext()
        GlUtil.focusFramebufferUsingCurrentContext(slot.fbo, slotWidth, slotHeight)
        GLES20.glDisable(GLES20.GL_BLEND)
        program.use()
        program.setSamplerTexIdUniform("uTex", video.textureId, 0)
        program.setFloatUniform("uOpacity", 1f)
        program.setBufferAttribute("aPos", FULL_FRAME, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setBufferAttribute("aUnit", FULL_UNIT, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setFloatsUniform("uTexMatrix", video.textureMatrix)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
        slot.timeUs = timeUs
        kept.addLast(slot)
    }

    private class Slot(val texture: Int, val fbo: Int) {
        var timeUs = 0L

        fun delete() {
            GlUtil.deleteFbo(fbo)
            GlUtil.deleteTexture(texture)
        }

        companion object {
            fun create(width: Int, height: Int): Slot {
                val texture = GlUtil.createTexture(width, height, false)
                return Slot(texture, GlUtil.createFboForTexture(texture))
            }
        }
    }

    companion object {
        /**
         * The memory the kept frames of one clip may take: 34 frames of a 720p proxy, 15 at
         * 1080p. Slots are made as the groups of pictures need them.
         */
        const val BUDGET_BYTES = 128L * 1024 * 1024

        /** Fewer kept frames than this decode a 60 fps group of pictures too many times over. */
        const val MIN_SLOTS = 15

        /** Two seconds at 60 fps: more is never needed for the usual groups of pictures. */
        const val MAX_SLOTS = 120

        private const val BYTES_PER_PIXEL = 4
        private val FULL_FRAME: FloatArray = GlUtil.getNormalizedCoordinateBounds()
        private val FULL_UNIT: FloatArray = GlUtil.getTextureCoordinateBounds()
    }
}
