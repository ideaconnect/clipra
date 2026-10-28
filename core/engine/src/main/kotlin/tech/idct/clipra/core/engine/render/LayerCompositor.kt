package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLUtils
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import androidx.annotation.OptIn
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/**
 * Draws a [RenderTimeline] as a Media3 effect. It replaces each input frame (a blank canvas from
 * the composition) with the layers at that frame's time, L1 first: video clips come from their
 * own decoders, photos and text from bitmaps, and each clip is blended onto the canvas with its
 * blend mode. Gaps draw nothing, so the layers below show through.
 *
 * Media3's own compositor blends only with alpha, and Transformer doesn't let an app replace it,
 * so Clipra composites here (docs/spikes/engine.md).
 */
@OptIn(UnstableApi::class)
class LayerCompositor(
    private val timeline: RenderTimeline,
    private val listener: Listener? = null,
) : GlEffect {
    /** Reports the time spent drawing each frame, for measurements. */
    fun interface Listener {
        fun onFrameDrawn(presentationTimeUs: Long, drawTimeNs: Long)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LayerCompositorProgram(context, timeline, listener)
}

@OptIn(UnstableApi::class)
private class LayerCompositorProgram(
    private val context: Context,
    private val timeline: RenderTimeline,
    private val listener: LayerCompositor.Listener?,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents= */
    false,
    /* texturePoolCapacity= */
    1,
) {
    private val callbackThread = HandlerThread("clipra-frames").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val videoSources = HashMap<String, VideoFrameSource>()
    private val bitmapTextures = HashMap<String, Int>()

    private val externalProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP_EXTERNAL)
    private val bitmapProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP_2D)
    private val blendProgram = GlProgram(Shaders.VERTEX, Shaders.BLEND)
    private val copyProgram = GlProgram(Shaders.VERTEX, Shaders.COPY)

    private val canvas = arrayOfNulls<Target>(2)
    private var current = 0
    private var clipTarget: Target? = null
    private val frameToleranceUs = 500_000L / timeline.frameRate
    private val flipY = FloatArray(16).apply {
        Matrix.setIdentityM(this, 0)
        Matrix.translateM(this, 0, 0f, 1f, 0f)
        Matrix.scaleM(this, 0, 1f, -1f, 1f)
    }
    private val identity = GlUtil.create4x4IdentityMatrix()

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        canvas[0] = Target.create(timeline.width, timeline.height)
        canvas[1] = Target.create(timeline.width, timeline.height)
        clipTarget = Target.create(timeline.width, timeline.height)
        return Size(timeline.width, timeline.height)
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val started = System.nanoTime()
        val output = IntArray(1).also {
            GLES20.glGetIntegerv(
                GLES20.GL_FRAMEBUFFER_BINDING,
                it,
                0,
            )
        }[0]

        val first = canvas[current]!!
        GlUtil.focusFramebufferUsingCurrentContext(first.fbo, first.width, first.height)
        val bg = timeline.background
        GLES20.glClearColor(Color.red(bg) / 255f, Color.green(bg) / 255f, Color.blue(bg) / 255f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)

        val active = HashSet<String>()
        for (layer in timeline.layers) {
            if (layer.hidden) continue
            for (clip in layer.clips) {
                if (!clip.isActiveAt(presentationTimeUs)) continue
                active += clip.id
                drawClip(clip, presentationTimeUs)
            }
        }
        releaseInactive(active)

        val result = canvas[current]!!
        GlUtil.focusFramebufferUsingCurrentContext(output, timeline.width, timeline.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTex", result.texture, 0)
        drawQuad(copyProgram, FULL_FRAME, identity)
        listener?.onFrameDrawn(presentationTimeUs, System.nanoTime() - started)
    }

    override fun flush() {
        super.flush()
        // A seek: decoders find their new position on the next frame.
    }

    override fun release() {
        super.release()
        videoSources.values.forEach { it.release() }
        videoSources.clear()
        bitmapTextures.values.forEach { GlUtil.deleteTexture(it) }
        bitmapTextures.clear()
        (canvas + clipTarget).filterNotNull().forEach { it.delete() }
        listOf(externalProgram, bitmapProgram, blendProgram, copyProgram).forEach { it.delete() }
        callbackThread.quitSafely()
    }

    private fun drawClip(clip: RenderClip, timeUs: Long) {
        val frame = clipFrame(clip, timeUs) ?: return
        val opacity = clip.opacityAt(timeUs)
        if (opacity <= 0f) return
        val position = quadFor(clip.placement, frame.width, frame.height)
        val program = if (frame.external) externalProgram else bitmapProgram
        if (clip.blend == BlendMode.Normal) {
            // Alpha blending straight onto the canvas.
            val target = canvas[current]!!
            GlUtil.focusFramebufferUsingCurrentContext(target.fbo, target.width, target.height)
            GLES20.glEnable(GLES20.GL_BLEND)
            GLES20.glBlendFuncSeparate(
                GLES20.GL_SRC_ALPHA,
                GLES20.GL_ONE_MINUS_SRC_ALPHA,
                GLES20.GL_ONE,
                GLES20.GL_ONE_MINUS_SRC_ALPHA,
            )
            drawSource(program, frame, opacity, position)
            GLES20.glDisable(GLES20.GL_BLEND)
            return
        }
        // Other modes read the canvas: draw the clip alone, then blend it into the other canvas.
        val clipLayer = clipTarget!!
        GlUtil.focusFramebufferUsingCurrentContext(clipLayer.fbo, clipLayer.width, clipLayer.height)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        drawSource(program, frame, opacity, position)

        val source = canvas[current]!!
        current = 1 - current
        val destination = canvas[current]!!
        GlUtil.focusFramebufferUsingCurrentContext(
            destination.fbo,
            destination.width,
            destination.height,
        )
        blendProgram.use()
        blendProgram.setSamplerTexIdUniform("uDst", source.texture, 0)
        blendProgram.setSamplerTexIdUniform("uSrc", clipLayer.texture, 1)
        blendProgram.setIntUniform("uMode", clip.blend.ordinal)
        drawQuad(blendProgram, FULL_FRAME, identity)
    }

    private fun drawSource(
        program: GlProgram,
        frame: ClipFrame,
        opacity: Float,
        position: FloatArray,
    ) {
        program.use()
        program.setSamplerTexIdUniform("uTex", frame.texture, 0)
        program.setFloatUniform("uOpacity", opacity)
        drawQuad(program, position, frame.textureMatrix)
    }

    private fun drawQuad(program: GlProgram, position: FloatArray, textureMatrix: FloatArray) {
        program.setBufferAttribute("aPos", position, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setBufferAttribute(
            "aUnit",
            GlUtil.getTextureCoordinateBounds(),
            GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE,
        )
        program.setFloatsUniform("uTexMatrix", textureMatrix)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    private fun clipFrame(clip: RenderClip, timeUs: Long): ClipFrame? =
        when (val source = clip.source) {
            is RenderSource.Video -> videoFrame(clip, source, timeUs)
            is RenderSource.Image -> imageFrame(clip, source)
            is RenderSource.Audio -> null
        }

    private fun videoFrame(clip: RenderClip, source: RenderSource.Video, timeUs: Long): ClipFrame? {
        val video = videoSources.getOrPut(clip.id) {
            VideoFrameSource(context, source.path, callbackHandler)
        }
        val sourceTimeUs = source.inPointUs + (timeUs - clip.startUs)
        if (!video.frameAt(sourceTimeUs, frameToleranceUs)) return null
        return ClipFrame(video.textureId, video.textureMatrix, video.width, video.height, true)
    }

    private fun imageFrame(clip: RenderClip, source: RenderSource.Image): ClipFrame {
        val texture = bitmapTextures.getOrPut(clip.id) {
            GlUtil.generateTexture().also { id ->
                GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, id, GLES20.GL_LINEAR)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, source.bitmap, 0)
            }
        }
        return ClipFrame(texture, flipY, source.bitmap.width, source.bitmap.height, false)
    }

    private fun releaseInactive(active: Set<String>) {
        videoSources.keys.filter { it !in active }.forEach { videoSources.remove(it)?.release() }
        bitmapTextures.keys.filter { it !in active }.forEach {
            bitmapTextures.remove(it)?.let { id -> GlUtil.deleteTexture(id) }
        }
    }

    /** The clip's quad in NDC: Fill covers the frame, Box fits inside the box. */
    private fun quadFor(placement: Placement, sourceWidth: Int, sourceHeight: Int): FloatArray {
        val frameW = timeline.width.toFloat()
        val frameH = timeline.height.toFloat()
        val (left, top, width, height) = when (placement) {
            Placement.Fill -> {
                val scale = maxOf(frameW / sourceWidth, frameH / sourceHeight)
                val w = sourceWidth * scale / frameW
                val h = sourceHeight * scale / frameH
                listOf((1 - w) / 2, (1 - h) / 2, w, h)
            }

            is Placement.Box -> {
                val boxW = placement.width * frameW
                val boxH = placement.height * frameH
                val scale = minOf(boxW / sourceWidth, boxH / sourceHeight)
                val w = sourceWidth * scale / frameW
                val h = sourceHeight * scale / frameH
                listOf(
                    placement.left + (placement.width - w) / 2,
                    placement.top + (placement.height - h) / 2,
                    w,
                    h,
                )
            }
        }
        val x0 = left * 2 - 1
        val x1 = (left + width) * 2 - 1
        val y0 = 1 - (top + height) * 2
        val y1 = 1 - top * 2
        return floatArrayOf(x0, y0, 0f, 1f, x1, y0, 0f, 1f, x0, y1, 0f, 1f, x1, y1, 0f, 1f)
    }

    private class ClipFrame(
        val texture: Int,
        val textureMatrix: FloatArray,
        val width: Int,
        val height: Int,
        val external: Boolean,
    )

    private class Target(val texture: Int, val fbo: Int, val width: Int, val height: Int) {
        fun delete() {
            GlUtil.deleteFbo(fbo)
            GlUtil.deleteTexture(texture)
        }

        companion object {
            fun create(width: Int, height: Int): Target {
                val texture = GlUtil.createTexture(width, height, false)
                return Target(texture, GlUtil.createFboForTexture(texture), width, height)
            }
        }
    }

    private companion object {
        val FULL_FRAME: FloatArray = GlUtil.getNormalizedCoordinateBounds()
    }
}
