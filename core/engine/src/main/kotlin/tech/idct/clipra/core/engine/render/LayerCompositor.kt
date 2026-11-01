package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
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
import tech.idct.clipra.core.engine.media.CutoutMasks
import tech.idct.clipra.core.model.Behind
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.CellRect
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.ColorMath
import tech.idct.clipra.core.model.Correction
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.CutoutMath
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.HueRange
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.Stabilizing
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.Transitions
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt
import tech.idct.clipra.core.model.Size as FrameSize

/**
 * Draws a [RenderTimeline] as a Media3 effect. It replaces each input frame (a blank canvas from
 * the composition) with the layers at that frame's time, L1 first: video clips come from their
 * own decoders, photos and text from bitmaps; each clip goes through its filters, is placed by
 * its transform and is blended onto the canvas with its blend mode, and transitions join clips
 * across cuts. Gaps draw nothing, so the layers below show through. [live] values, while set,
 * replace the clips' own (a gesture in progress).
 *
 * Media3's own compositor blends only with alpha, and Transformer doesn't let an app replace it,
 * so Clipra composites here (docs/spikes/engine.md).
 */
@OptIn(UnstableApi::class)
class LayerCompositor(
    private val timeline: RenderTimeline,
    private val listener: Listener? = null,
    private val gate: PauseGate? = null,
    private val live: LiveEdits? = null,
) : GlEffect {
    /** Reports the time spent drawing each frame, for measurements. */
    fun interface Listener {
        fun onFrameDrawn(presentationTimeUs: Long, drawTimeNs: Long)
    }

    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        LayerCompositorProgram(context, timeline, listener, gate, live)
}

@OptIn(UnstableApi::class)
private class LayerCompositorProgram(
    private val context: Context,
    private val timeline: RenderTimeline,
    private val listener: LayerCompositor.Listener?,
    private val gate: PauseGate?,
    private val live: LiveEdits?,
) : BaseGlShaderProgram(
    /* useHighPrecisionColorComponents= */
    false,
    /* texturePoolCapacity= */
    1,
) {
    private val callbackThread = HandlerThread("clipra-frames").apply { start() }
    private val callbackHandler = Handler(callbackThread.looper)
    private val videoSources = HashMap<String, VideoFrameSource>()

    /** Each video clip's latest frame, copied out of its decoder's texture ([videoFrame]). */
    private val frameCopies = HashMap<String, FrameCopy>()
    private val bitmapTextures = HashMap<String, Int>()
    private val drawnSizes = HashMap<String, Pair<Int, Int>>()

    private val externalProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP_EXTERNAL)
    private val bitmapProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP_2D)
    private val blendProgram = GlProgram(Shaders.VERTEX, Shaders.BLEND)
    private val copyProgram = GlProgram(Shaders.VERTEX, Shaders.COPY)
    private val filterProgram = GlProgram(Shaders.VERTEX, Shaders.FILTER)
    private val blurProgram = GlProgram(Shaders.VERTEX, Shaders.BLUR)
    private val transitionProgram = GlProgram(Shaders.VERTEX, Shaders.TRANSITION)
    private val gradeProgram = GlProgram(Shaders.VERTEX, Shaders.GRADE)
    private val cutoutProgram = GlProgram(Shaders.VERTEX, Shaders.CUTOUT)

    /** Each cut-out clip's mask texture (#50) and the frame it holds. */
    private val maskTextures = HashMap<String, MaskTexture>()
    private val maskBytes = ByteArray(CutoutMasks.SIZE * CutoutMasks.SIZE)

    // LUTs (#28) as textures, by table; a few hundred kilobytes each.
    private val lutTextures = HashMap<CubeLut, LutTexture>()

    // The two clips of a transition, each drawn alone; made on the first transition.
    private var transitionTargets: Array<Target>? = null

    private val canvas = arrayOfNulls<Target>(2)
    private var current = 0
    private var clipTarget: Target? = null

    // Pairs of textures for filtering clips, by size; sizes no clip used in a frame are freed.
    private val effectTargets = HashMap<Long, Array<Target>>()
    private val effectSizesUsed = HashSet<Long>()
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
        // A paused export holds the frame here; the encoder and muxer simply wait.
        gate?.awaitOpen()
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
            val transition = layer.transitions.firstOrNull { it.isActiveAt(presentationTimeUs) }
            val from = transition?.let { t -> layer.clips.firstOrNull { it.id == t.fromClipId } }
            val to = transition?.let { t -> layer.clips.firstOrNull { it.id == t.toClipId } }
            var transitionDrawn = false
            for (clip in layer.clips) {
                if (transition != null && from != null && to != null &&
                    (clip.id == from.id || clip.id == to.id)
                ) {
                    if (!transitionDrawn) {
                        active += from.id
                        active += to.id
                        drawTransition(transition, from, to, presentationTimeUs)
                        transitionDrawn = true
                    }
                    continue
                }
                if (!clip.isActiveAt(presentationTimeUs)) continue
                active += clip.id
                if (clip.source == RenderSource.Adjustment) {
                    drawAdjustment(clip, presentationTimeUs)
                } else {
                    drawClip(clip, presentationTimeUs)
                }
            }
        }
        releaseInactive(active)
        effectTargets.keys.filter { it !in effectSizesUsed }.forEach { size ->
            effectTargets.remove(size)?.forEach { it.delete() }
        }
        effectSizesUsed.clear()

        val result = canvas[current]!!
        GlUtil.focusFramebufferUsingCurrentContext(output, timeline.width, timeline.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        copyProgram.use()
        copyProgram.setSamplerTexIdUniform("uTex", result.texture, 0)
        drawQuad(copyProgram, FULL_FRAME, FULL_UNIT, identity)
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
        frameCopies.values.forEach { it.target.delete() }
        frameCopies.clear()
        bitmapTextures.values.forEach { GlUtil.deleteTexture(it) }
        bitmapTextures.clear()
        (canvas + clipTarget).filterNotNull().forEach { it.delete() }
        transitionTargets?.forEach { it.delete() }
        transitionTargets = null
        effectTargets.values.forEach { pair -> pair.forEach { it.delete() } }
        effectTargets.clear()
        lutTextures.values.forEach { GlUtil.deleteTexture(it.id) }
        lutTextures.clear()
        val programs = listOf(
            externalProgram,
            bitmapProgram,
            blendProgram,
            copyProgram,
            filterProgram,
            blurProgram,
            transitionProgram,
            gradeProgram,
            cutoutProgram,
        )
        programs.forEach { it.delete() }
        maskTextures.values.forEach { GlUtil.deleteTexture(it.id) }
        maskTextures.clear()
        callbackThread.quitSafely()
    }

    /** A clip's picture at a time, through its filters, with where it lands. */
    private class Prepared(
        val frame: ClipFrame,
        val opacity: Float,
        val position: FloatArray,
        val unit: FloatArray,
        /** The mosaic cell (#17) the picture is cut to, or null. */
        val cell: CellRect?,
    )

    private fun prepare(clip: RenderClip, timeUs: Long): Prepared? {
        val raw = clipFrame(clip, timeUs) ?: return null
        // Keyframed values at this frame (#21); a gesture in progress shows its own. The
        // animation (#33) moves and fades from there.
        val motion = clip.motionAt(timeUs, live?.transform(clip.id) ?: clip.transformAt(timeUs))
        val keyed = live?.opacity(clip.id) ?: clip.keyedOpacityAt(timeUs)
        val opacity = clip.opacityAt(timeUs, keyed * motion.opacity)
        if (opacity <= 0f) return null
        // The stack in order (#29); a disabled effect draws nothing.
        val stack = live?.effects(clip.id) ?: clip.effects
        val filters = stack.filter { it.enabled }.mapNotNull { effect ->
            FilterRecipes.of(effect.type)?.takeIf { effect.strength > 0f }?.let { it to effect }
        }
        val mask = live?.mask(clip.id) ?: clip.maskAt(timeUs)
        // The grade (#28) comes first; the Color board shows its own while a slider moves.
        val liveColor = live?.color(clip.id)
        val grading = (liveColor?.grade ?: clip.color)?.takeIf { !it.isNeutral }?.let {
            Grading(it, if (liveColor != null) liveColor.lut else clip.lut, live?.split(clip.id))
        }
        // The person cutout (#50) comes last, over the graded and filtered picture.
        val cutout = clip.cutout?.let { cut ->
            val settings = live?.cutout(clip.id) ?: cut.settings
            // The person cutout needs its masks; a chroma key (#51) doesn't.
            if (settings.key == null && cut.masks == null) {
                null
            } else {
                Cutting(clip.id, settings, cut.masks, mediaTimeAt(clip, timeUs))
            }
        }
        // Stabilization (#54) moves the frame onto the steadied path, before everything else.
        val steady = clip.stabilized?.let { stabilized ->
            stabilizationMatrix(
                stabilized.at(mediaTimeAt(clip, timeUs)),
                stabilized.zoom,
                raw.width.toFloat() / raw.height,
            )
        }
        val frame = if (filters.isEmpty() && grading == null && cutout == null && steady == null) {
            raw
        } else {
            filtered(raw, filters, mask, timeUs, grading, cutout, steady)
        }
        val (position, unit) = quadFor(clip, motion.transform, frame.width, frame.height)
        return Prepared(frame, opacity, position, unit, clip.cell)
    }

    /** Draws [prepared] alone into [target], over transparency. */
    private fun drawAlone(target: Target, prepared: Prepared?) {
        GlUtil.focusFramebufferUsingCurrentContext(target.fbo, target.width, target.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (prepared == null) return
        val program = if (prepared.frame.external) externalProgram else bitmapProgram
        inCell(prepared.cell) {
            drawSource(program, prepared.frame, prepared.opacity, prepared.position, prepared.unit)
        }
    }

    /** Runs [draw] cut to [cell] (#17): nothing of the clip shows outside its cell. */
    private inline fun inCell(cell: CellRect?, draw: () -> Unit) {
        if (cell == null) {
            draw()
            return
        }
        val left = (cell.left * timeline.width).roundToInt()
        val right = ((cell.left + cell.width) * timeline.width).roundToInt()
        // GL counts rows from the bottom.
        val bottom = ((1 - cell.top - cell.height) * timeline.height).roundToInt()
        val top = ((1 - cell.top) * timeline.height).roundToInt()
        GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
        GLES20.glScissor(left, bottom, right - left, top - bottom)
        draw()
        GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
    }

    private fun drawClip(clip: RenderClip, timeUs: Long) {
        val prepared = prepare(clip, timeUs) ?: return
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
            val program = if (prepared.frame.external) externalProgram else bitmapProgram
            inCell(prepared.cell) {
                drawSource(
                    program,
                    prepared.frame,
                    prepared.opacity,
                    prepared.position,
                    prepared.unit,
                )
            }
            GLES20.glDisable(GLES20.GL_BLEND)
            return
        }
        // Other modes read the canvas: draw the clip alone, then blend it into the other canvas.
        drawAlone(clipTarget!!, prepared)
        blendLayer(clip.blend)
    }

    /**
     * An adjustment clip (#23): its grade and effects run over everything drawn so far (the
     * layers below it), and the result goes back over them at the clip's opacity, in its cell,
     * so a fade in or out eases the look in and out. A mask limits the effects as on any clip.
     */
    private fun drawAdjustment(clip: RenderClip, timeUs: Long) {
        val keyed = live?.opacity(clip.id) ?: clip.keyedOpacityAt(timeUs)
        val opacity = clip.opacityAt(timeUs, keyed)
        if (opacity <= 0f) return
        val stack = live?.effects(clip.id) ?: clip.effects
        val filters = stack.filter { it.enabled }.mapNotNull { effect ->
            FilterRecipes.of(effect.type)?.takeIf { effect.strength > 0f }?.let { it to effect }
        }
        val liveColor = live?.color(clip.id)
        val grading = (liveColor?.grade ?: clip.color)?.takeIf { !it.isNeutral }?.let {
            Grading(it, if (liveColor != null) liveColor.lut else clip.lut, live?.split(clip.id))
        }
        if (filters.isEmpty() && grading == null) return
        val below = canvas[current]!!
        val mask = live?.mask(clip.id) ?: clip.maskAt(timeUs)
        val adjusted = filtered(
            ClipFrame(below.texture, identity, below.width, below.height, false),
            filters,
            mask,
            timeUs,
            grading,
        )
        GlUtil.focusFramebufferUsingCurrentContext(below.fbo, below.width, below.height)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFuncSeparate(
            GLES20.GL_SRC_ALPHA,
            GLES20.GL_ONE_MINUS_SRC_ALPHA,
            GLES20.GL_ONE,
            GLES20.GL_ONE_MINUS_SRC_ALPHA,
        )
        inCell(clip.cell) {
            drawSource(bitmapProgram, adjusted, opacity, FULL_FRAME, FULL_UNIT)
        }
        GLES20.glDisable(GLES20.GL_BLEND)
    }

    /**
     * A transition: both clips drawn alone (the outgoing one past its end, the incoming one
     * before its start), combined by the transition shader, then blended like one clip, with
     * the outgoing clip's mode for the first half and the incoming one's after.
     */
    private fun drawTransition(
        transition: RenderTransition,
        from: RenderClip,
        to: RenderClip,
        timeUs: Long,
    ) {
        val targets = transitionTargets ?: arrayOf(
            Target.create(timeline.width, timeline.height),
            Target.create(timeline.width, timeline.height),
        ).also { transitionTargets = it }
        drawAlone(targets[0], prepare(from, timeUs))
        drawAlone(targets[1], prepare(to, timeUs))
        val linear = (timeUs - transition.startUs).toFloat() / transition.durationUs
        val progress = Transitions.ease(transition.easing, linear)

        val region = transition.region ?: RenderTransition.Region(0f, 0f, 1f, 1f)
        val clipLayer = clipTarget!!
        GlUtil.focusFramebufferUsingCurrentContext(clipLayer.fbo, clipLayer.width, clipLayer.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        transitionProgram.use()
        transitionProgram.setSamplerTexIdUniform("uA", targets[0].texture, 0)
        transitionProgram.setSamplerTexIdUniform("uB", targets[1].texture, 1)
        transitionProgram.setFloatUniform("uProgress", progress)
        transitionProgram.setIntUniform("uType", Shaders.transitionIndex(transition.type))
        transitionProgram.setFloatsUniform(
            "uRegion",
            floatArrayOf(region.left, 1 - region.top - region.height, region.width, region.height),
        )
        val aspect = region.width * timeline.width / (region.height * timeline.height)
        transitionProgram.setFloatUniform("uAspect", aspect)
        transitionProgram.setFloatUniform("uTime", timeUs / 1_000_000f)
        drawQuad(transitionProgram, FULL_FRAME, FULL_UNIT, identity)
        blendLayer(if (progress < 0.5f) from.blend else to.blend)
    }

    /** Blends the clip layer (straight alpha) into the other canvas with [mode]. */
    private fun blendLayer(mode: BlendMode) {
        val clipLayer = clipTarget!!
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
        blendProgram.setIntUniform("uMode", Shaders.blendIndex(mode))
        drawQuad(blendProgram, FULL_FRAME, FULL_UNIT, identity)
    }

    /** A grade to draw (#28), its LUT, and where the before and after divide (or null). */
    private class Grading(val grade: ColorGrade, val lut: CubeLut?, val split: Float?)

    /** A cutout to draw (#50): its settings and masks, at [mediaUs] of the clip's media. */
    private class Cutting(
        val clipId: String,
        val settings: Cutout,
        val masks: CutoutMasks?,
        val mediaUs: Long,
    )

    /** A clip's mask texture and the frame of its masks it holds. */
    private class MaskTexture(val id: Int, var frame: Int, var masks: CutoutMasks)

    /**
     * Stabilization (#54) as a texture matrix on the picture's unit coordinates (origin at the
     * bottom-left): each point of the steadied picture reads from where `Stabilizing.source`
     * says, in the frame as shot. [aspect] is the picture's width over its height.
     */
    private fun stabilizationMatrix(
        correction: Correction,
        zoom: Float,
        aspect: Float,
    ): FloatArray {
        fun read(u: Float, v: Float): Pair<Float, Float> {
            val (px, py) = Stabilizing.source(correction, zoom, u - 0.5f, (0.5f - v) / aspect)
            return (px + 0.5f) to (0.5f - py * aspect)
        }
        val (bx, by) = read(0f, 0f)
        val (ux, uy) = read(1f, 0f)
        val (vx, vy) = read(0f, 1f)
        return floatArrayOf(
            ux - bx, uy - by, 0f, 0f,
            vx - bx, vy - by, 0f, 0f,
            0f, 0f, 1f, 0f,
            bx, by, 0f, 1f,
        )
    }

    /** The media time a clip shows at [timeUs]: a photo has one picture. */
    private fun mediaTimeAt(clip: RenderClip, timeUs: Long): Long =
        when (val source = clip.source) {
            is RenderSource.Video -> source.inPointUs + clip.sourceOffsetAt(timeUs)
            else -> 0
        }

    /** A LUT's slices in a grid of tiles, [columns] across and [rows] down. */
    private class LutTexture(val id: Int, val columns: Int, val rows: Int)

    /**
     * The clip's picture through its [grading] and [filters]: drawn upright into an effect
     * texture (no larger than the frame), graded, then one pass per filter, two for a blur. The
     * [mask] limits one of the filters.
     */
    private fun filtered(
        frame: ClipFrame,
        filters: List<Pair<FilterRecipe, Effect>>,
        mask: Mask?,
        timeUs: Long,
        grading: Grading?,
        cutout: Cutting? = null,
        steady: FloatArray? = null,
    ): ClipFrame {
        val longest = maxOf(timeline.width, timeline.height).toFloat()
        val scale = minOf(1f, longest / maxOf(frame.width, frame.height))
        val width = maxOf(2, (frame.width * scale).roundToInt())
        val height = maxOf(2, (frame.height * scale).roundToInt())
        val key = width.toLong() shl 32 or height.toLong()
        effectSizesUsed += key
        val targets = effectTargets.getOrPut(key) {
            Array(3) { Target.create(width, height) }
        }
        fun focus(target: Target) {
            GlUtil.focusFramebufferUsingCurrentContext(target.fbo, target.width, target.height)
            GLES20.glDisable(GLES20.GL_BLEND)
        }
        var current = 0
        focus(targets[0])
        GLES20.glClearColor(0f, 0f, 0f, 0f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        val source = if (frame.external) externalProgram else bitmapProgram
        if (steady == null) {
            drawSource(source, frame, 1f, FULL_FRAME, FULL_UNIT)
        } else {
            // Read the frame through the correction: the texture's own matrix after it.
            val matrix = FloatArray(16)
            Matrix.multiplyMM(matrix, 0, frame.textureMatrix, 0, steady, 0)
            source.use()
            source.setSamplerTexIdUniform("uTex", frame.texture, 0)
            source.setFloatUniform("uOpacity", 1f)
            drawQuad(source, FULL_FRAME, FULL_UNIT, matrix)
        }
        if (grading != null) {
            focus(targets[1])
            gradePass(targets[0], grading)
            current = 1
        }

        // The mask limits the effect it names, or the first filter.
        val maskedType = mask?.takeIf { it.shape != MaskShape.None }
            ?.let { it.effect ?: filters.firstOrNull()?.second?.type }

        for ((recipe, effect) in filters) {
            val effectMask = mask.takeIf { effect.type == maskedType }
            if (recipe.blur > 0f) {
                // Blur's strength is its radius; a mask then fades it against the original.
                val deviation = recipe.blur * effect.strength * minOf(width, height)
                val across = (current + 1) % 3
                val down = (current + 2) % 3
                blur(targets[current], targets[across], deviation / 4 / width, 0f)
                blur(targets[across], targets[down], 0f, deviation / 4 / height)
                if (effectMask == null) {
                    current = down
                } else {
                    focus(targets[across])
                    filterPass(
                        targets[down],
                        targets[current],
                        FilterRecipe(),
                        Effect(effect.type, 1f),
                        effectMask,
                        width,
                        height,
                        timeUs,
                    )
                    current = across
                }
                continue
            }
            val next = (current + 1) % 3
            focus(targets[next])
            filterPass(
                targets[current],
                targets[current],
                recipe,
                effect,
                effectMask,
                width,
                height,
                timeUs,
            )
            current = next
        }
        if (cutout != null) {
            val blurred = (current + 2) % 3
            if (cutout.settings.behind == Behind.Blur) {
                // A strong blur of the clip itself goes behind the person.
                val across = (current + 1) % 3
                val deviation = BEHIND_BLUR * minOf(width, height)
                blur(targets[current], targets[across], deviation / 4 / width, 0f)
                blur(targets[across], targets[blurred], 0f, deviation / 4 / height)
            }
            val next = (current + 1) % 3
            focus(targets[next])
            cutoutPass(targets[current], targets[blurred], cutout, steady ?: identity)
            current = next
        }
        val result = targets[current]
        return ClipFrame(result.texture, identity, width, height, external = false)
    }

    /** The cutout pass (#50) into the focused target, as `CutoutMath.alpha` keeps a pixel. */
    private fun cutoutPass(
        input: Target,
        blurred: Target,
        cutout: Cutting,
        maskMatrix: FloatArray,
    ) {
        val settings = cutout.settings
        val program = cutoutProgram
        program.use()
        program.setSamplerTexIdUniform("uTex", input.texture, 0)
        val key = settings.key
        // A chroma key reads no mask; the sampler still needs a texture.
        val mask = if (key == null) maskTexture(cutout) else input.texture
        program.setSamplerTexIdUniform("uMask", mask, 1)
        program.setSamplerTexIdUniform("uBlurred", blurred.texture, 2)
        val behind = when (settings.behind) {
            Behind.LayerBelow, Behind.Clear -> 0
            Behind.Blur -> 1
            Behind.Color -> 2
        }
        program.setIntUniform("uBehind", behind)
        val color = settings.color.toInt()
        program.setFloatsUniform(
            "uColor",
            floatArrayOf(
                Color.red(color) / 255f,
                Color.green(color) / 255f,
                Color.blue(color) / 255f,
            ),
        )
        program.setFloatUniform("uRefine", settings.edgeRefine)
        program.setFloatUniform("uHair", settings.hairDetail)
        program.setIntUniform("uKeyed", if (key != null) 1 else 0)
        val keyColor = (key?.color ?: 0L).toInt()
        program.setFloatsUniform(
            "uKey",
            CutoutMath.chroma(
                Color.red(keyColor) / 255f,
                Color.green(keyColor) / 255f,
                Color.blue(keyColor) / 255f,
            ),
        )
        program.setFloatUniform("uSimilarity", key?.similarity ?: 0f)
        program.setFloatUniform("uSmoothness", key?.smoothness ?: 0f)
        program.setFloatUniform("uSpill", key?.spill ?: 0f)
        program.setFloatsUniform("uMaskMatrix", maskMatrix)
        drawQuad(program, FULL_FRAME, FULL_UNIT, identity)
    }

    /** The clip's mask at its media time, uploaded when the frame changes. */
    private fun maskTexture(cutout: Cutting): Int {
        val masks = cutout.masks!!
        val frame = masks.indexAt(cutout.mediaUs)
        val held = maskTextures[cutout.clipId]
        if (held != null && held.frame == frame && held.masks === masks) return held.id
        masks.frame(frame, maskBytes)
        val id = held?.id ?: GlUtil.generateTexture()
        GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, id, GLES20.GL_LINEAR)
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_LUMINANCE,
            CutoutMasks.SIZE,
            CutoutMasks.SIZE,
            0,
            GLES20.GL_LUMINANCE,
            GLES20.GL_UNSIGNED_BYTE,
            ByteBuffer.wrap(maskBytes),
        )
        GlUtil.checkGlError()
        if (held == null) {
            maskTextures[cutout.clipId] = MaskTexture(id, frame, masks)
        } else {
            held.frame = frame
            held.masks = masks
        }
        return id
    }

    /** The grade pass (#28) into the focused target, as `ColorMath` grades a pixel. */
    private fun gradePass(input: Target, grading: Grading) {
        val grade = grading.grade
        val program = gradeProgram
        program.use()
        program.setSamplerTexIdUniform("uTex", input.texture, 0)
        val exposure = 2f.pow(grade.exposure / 100f / ColorMath.GAMMA)
        val warm = ColorMath.TEMPERATURE * grade.temperature / 100f
        val magenta = ColorMath.TINT * grade.tint / 100f
        program.setFloatsUniform(
            "uGain",
            floatArrayOf(exposure * (1 + warm), exposure * (1 - magenta), exposure * (1 - warm)),
        )
        program.setFloatUniform("uContrast", 1 + ColorMath.CONTRAST * grade.contrast / 100f)
        program.setFloatUniform("uSaturation", 1 + grade.saturation / 100f)
        val shifts = ColorMath.shifts(grade)
        program.setIntUniform("uHasHsl", if (shifts.any { it != 0f }) 1 else 0)
        for (i in 0 until HueRange.entries.size) {
            program.setFloatsUniform("uH$i", shifts.copyOfRange(i * 3, i * 3 + 3))
        }
        val lut = grading.lut?.takeIf { grade.lut != null && grade.lutStrength > 0f }
        val texture = lut?.let(::lutTexture)
        program.setIntUniform("uHasLut", if (texture != null) 1 else 0)
        // Without a LUT the sampler still needs a texture; the shader doesn't read it.
        program.setSamplerTexIdUniform("uLut", texture?.id ?: input.texture, 1)
        program.setFloatUniform("uLutSize", (lut?.size ?: 2).toFloat())
        program.setFloatsUniform(
            "uLutGrid",
            floatArrayOf((texture?.columns ?: 1).toFloat(), (texture?.rows ?: 1).toFloat()),
        )
        program.setFloatsUniform("uLutMin", lut?.domainMin ?: floatArrayOf(0f, 0f, 0f))
        program.setFloatsUniform("uLutMax", lut?.domainMax ?: floatArrayOf(1f, 1f, 1f))
        program.setFloatUniform("uLutStrength", grade.lutStrength.coerceIn(0f, 1f))
        program.setFloatUniform("uVignette", grade.vignette / 100f)
        program.setFloatUniform("uSplit", grading.split ?: -1f)
        drawQuad(program, FULL_FRAME, FULL_UNIT, identity)
    }

    /** [lut] as a texture: blue picks the tile, red runs across it and green down. */
    private fun lutTexture(lut: CubeLut): LutTexture = lutTextures.getOrPut(lut) {
        val n = lut.size
        val columns = ceil(sqrt(n.toDouble())).toInt()
        val rows = (n + columns - 1) / columns
        val width = columns * n
        val height = rows * n
        val pixels = ByteBuffer.allocateDirect(width * height * 4).order(ByteOrder.nativeOrder())
        for (b in 0 until n) {
            val left = b % columns * n
            val top = b / columns * n
            for (g in 0 until n) {
                for (r in 0 until n) {
                    val i = lut.index(r, g, b)
                    val at = ((top + g) * width + left + r) * 4
                    for (c in 0 until 3) {
                        val value = (lut.values[i + c].coerceIn(0f, 1f) * 255 + 0.5f).toInt()
                        pixels.put(at + c, value.toByte())
                    }
                    pixels.put(at + 3, 255.toByte())
                }
            }
        }
        val ids = IntArray(1)
        GLES20.glGenTextures(1, ids, 0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, ids[0])
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MIN_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_MAG_FILTER,
            GLES20.GL_LINEAR,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_S,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexParameteri(
            GLES20.GL_TEXTURE_2D,
            GLES20.GL_TEXTURE_WRAP_T,
            GLES20.GL_CLAMP_TO_EDGE,
        )
        GLES20.glTexImage2D(
            GLES20.GL_TEXTURE_2D,
            0,
            GLES20.GL_RGBA,
            width,
            height,
            0,
            GLES20.GL_RGBA,
            GLES20.GL_UNSIGNED_BYTE,
            pixels,
        )
        GlUtil.checkGlError()
        LutTexture(ids[0], columns, rows)
    }

    private fun blur(from: Target, to: Target, stepX: Float, stepY: Float) {
        GlUtil.focusFramebufferUsingCurrentContext(to.fbo, to.width, to.height)
        GLES20.glDisable(GLES20.GL_BLEND)
        blurProgram.use()
        blurProgram.setSamplerTexIdUniform("uTex", from.texture, 0)
        blurProgram.setFloatsUniform("uStep", floatArrayOf(stepX, stepY))
        drawQuad(blurProgram, FULL_FRAME, FULL_UNIT, identity)
    }

    /** One filter pass into the focused target: [input] filtered, mixed with [original]. */
    private fun filterPass(
        input: Target,
        original: Target,
        recipe: FilterRecipe,
        effect: Effect,
        mask: Mask?,
        width: Int,
        height: Int,
        timeUs: Long,
    ) {
        filterProgram.use()
        filterProgram.setSamplerTexIdUniform("uTex", input.texture, 0)
        filterProgram.setSamplerTexIdUniform("uOriginal", original.texture, 1)
        setFilterUniforms(recipe, effect, width, height, timeUs)
        setMaskUniforms(mask)
        drawQuad(filterProgram, FULL_FRAME, FULL_UNIT, identity)
    }

    private fun setMaskUniforms(mask: Mask?) {
        val program = filterProgram
        val shape = when (mask?.shape) {
            MaskShape.Rectangle -> 1
            MaskShape.Oval -> 2
            else -> 0
        }
        program.setIntUniform("uMaskShape", shape)
        program.setFloatsUniform("uMaskCentre", floatArrayOf(mask?.x ?: 0.5f, mask?.y ?: 0.5f))
        program.setFloatsUniform("uMaskSize", floatArrayOf(mask?.width ?: 1f, mask?.height ?: 1f))
        program.setFloatUniform(
            "uMaskTurn",
            Math.toRadians((mask?.rotation ?: 0f).toDouble()).toFloat(),
        )
        program.setFloatUniform("uMaskFeather", mask?.feather ?: 0f)
        program.setFloatUniform("uMaskRadius", mask?.cornerRadius ?: 0f)
        program.setFloatUniform("uMaskInvert", if (mask?.invert == true) 1f else 0f)
    }

    private fun setFilterUniforms(
        recipe: FilterRecipe,
        effect: Effect,
        width: Int,
        height: Int,
        timeUs: Long,
    ) {
        val program = filterProgram
        program.setFloatsUniform("uSize", floatArrayOf(width.toFloat(), height.toFloat()))
        program.setFloatsUniform("uMatrix", recipe.matrix.toGl())
        program.setIntUniform("uSampling", recipe.sampling.ordinal)
        val block = (effect.params[Filters.MOSAIC_SIZE] ?: Filters.DEFAULT_MOSAIC_SIZE) *
            minOf(width, height)
        program.setFloatsUniform("uBlock", floatArrayOf(block / width, block / height))
        program.setFloatUniform("uTime", timeUs / 1_000_000f)
        program.setFloatUniform("uStrength", effect.strength.coerceIn(0f, 1f))
        val overlay = recipe.overlay
        val none = FloatArray(4)
        val (kind, gradient, stops) = when (overlay) {
            null -> Triple(0, none, emptyList())

            is Overlay.Linear -> {
                val angle = Math.toRadians(overlay.degrees.toDouble())
                val direction = floatArrayOf(sin(angle).toFloat(), -cos(angle).toFloat(), 0f, 0f)
                Triple(1, direction, overlay.stops)
            }

            is Overlay.Radial -> {
                val shape = if (overlay.ellipse) 1f else 0f
                Triple(2, floatArrayOf(overlay.x, overlay.y, shape, 0f), overlay.stops)
            }

            Overlay.GlitchLines -> Triple(3, none, emptyList())

            Overlay.Scanlines -> Triple(4, none, emptyList())

            Overlay.Grain -> Triple(5, none, emptyList())

            Overlay.Halftone -> Triple(6, none, emptyList())
        }
        program.setIntUniform("uOverlay", kind)
        program.setIntUniform("uMode", Shaders.blendIndex(overlay?.blend ?: BlendMode.Normal))
        program.setFloatsUniform("uGradient", gradient)
        // Four stops; fewer repeat the last one.
        val last = stops.lastOrNull() ?: Stop(1f, 0f, 0f, 0f, 0f)
        val padded = List(4) { stops.getOrNull(it) ?: last }
        program.setFloatsUniform("uStops", FloatArray(4) { padded[it].position })
        padded.forEachIndexed { i, stop ->
            program.setFloatsUniform("uColor" + i, floatArrayOf(stop.r, stop.g, stop.b, stop.a))
        }
    }

    private fun drawSource(
        program: GlProgram,
        frame: ClipFrame,
        opacity: Float,
        position: FloatArray,
        unit: FloatArray,
    ) {
        program.use()
        program.setSamplerTexIdUniform("uTex", frame.texture, 0)
        program.setFloatUniform("uOpacity", opacity)
        drawQuad(program, position, unit, frame.textureMatrix)
    }

    private fun drawQuad(
        program: GlProgram,
        position: FloatArray,
        unit: FloatArray,
        textureMatrix: FloatArray,
    ) {
        program.setBufferAttribute("aPos", position, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setBufferAttribute("aUnit", unit, GlUtil.HOMOGENEOUS_COORDINATE_VECTOR_SIZE)
        program.setFloatsUniform("uTexMatrix", textureMatrix)
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    private fun clipFrame(clip: RenderClip, timeUs: Long): ClipFrame? =
        when (val source = clip.source) {
            is RenderSource.Video -> videoFrame(clip, source, timeUs)
            is RenderSource.Image -> imageFrame(clip, source.bitmap)
            is RenderSource.Drawn -> drawnFrame(clip, source)
            is RenderSource.Audio, RenderSource.Adjustment -> null
        }

    /**
     * The clip's frame at [timeUs], from a copy made as each frame comes out of its decoder, as
     * Media3 copies its inputs. A decoder's texture is only safe to read until another decoder
     * shows a frame: a frame held meanwhile (a clip past its media in a transition, a slowed
     * clip, a lower frame rate than the project's) showed the other decoder's picture on the
     * emulator, and a dissolve across such a cut came out as a hard cut.
     */
    private fun videoFrame(clip: RenderClip, source: RenderSource.Video, timeUs: Long): ClipFrame? {
        val video = videoSources.getOrPut(clip.id) {
            VideoFrameSource(context, source.path, callbackHandler)
        }
        // In a transition the clip runs on past its end or starts early; outside the file the
        // nearest frame holds.
        val frameUs = 1_000_000L / timeline.frameRate
        val sourceTimeUs = (source.inPointUs + clip.sourceOffsetAt(timeUs))
            .coerceIn(0, maxOf(0, source.durationUs - frameUs))
        if (!video.frameAt(sourceTimeUs, frameToleranceUs)) return null
        // Full size, up to twice the frame's longest side, so zooming in keeps its detail.
        val longest = 2f * maxOf(timeline.width, timeline.height)
        val scale = minOf(1f, longest / maxOf(video.width, video.height))
        val width = maxOf(2, (video.width * scale).roundToInt())
        val height = maxOf(2, (video.height * scale).roundToInt())
        val copy = frameCopies[clip.id]?.takeIf {
            it.target.width == width && it.target.height == height
        } ?: FrameCopy(Target.create(width, height)).also {
            frameCopies.put(clip.id, it)?.target?.delete()
        }
        if (copy.frames != video.shownFrames) {
            GlUtil.focusFramebufferUsingCurrentContext(copy.target.fbo, width, height)
            GLES20.glDisable(GLES20.GL_BLEND)
            val frame = ClipFrame(video.textureId, video.textureMatrix, width, height, true)
            drawSource(externalProgram, frame, 1f, FULL_FRAME, FULL_UNIT)
            copy.frames = video.shownFrames
        }
        return ClipFrame(copy.target.texture, identity, video.width, video.height, false)
    }

    /** A clip's copied frame, and which of its decoder's frames it holds. */
    private class FrameCopy(val target: Target, var frames: Long = -1)

    private fun imageFrame(clip: RenderClip, bitmap: Bitmap): ClipFrame {
        val texture = bitmapTextures.getOrPut(clip.id) { upload(bitmap) }
        return ClipFrame(texture, flipY, bitmap.width, bitmap.height, false)
    }

    /** Draws the picture once, when it first shows, and keeps only its texture. */
    private fun drawnFrame(clip: RenderClip, source: RenderSource.Drawn): ClipFrame {
        if (clip.id !in bitmapTextures) {
            val bitmap = source.draw()
            bitmapTextures[clip.id] = upload(bitmap)
            drawnSizes[clip.id] = bitmap.width to bitmap.height
            bitmap.recycle()
        }
        val (width, height) = drawnSizes.getValue(clip.id)
        return ClipFrame(bitmapTextures.getValue(clip.id), flipY, width, height, false)
    }

    private fun upload(bitmap: Bitmap): Int = GlUtil.generateTexture().also { id ->
        GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, id, GLES20.GL_LINEAR)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
    }

    private fun releaseInactive(active: Set<String>) {
        maskTextures.keys.filter { it !in active }.forEach {
            maskTextures.remove(it)?.let { texture -> GlUtil.deleteTexture(texture.id) }
        }
        videoSources.keys.filter { it !in active }.forEach { videoSources.remove(it)?.release() }
        frameCopies.keys.filter { it !in active }.forEach {
            frameCopies.remove(it)?.target?.delete()
        }
        bitmapTextures.keys.filter { it !in active }.forEach {
            bitmapTextures.remove(it)?.let { id -> GlUtil.deleteTexture(id) }
            drawnSizes.remove(it)
        }
    }

    /**
     * The clip's quad in NDC and the matching unit texture coordinates, from [ClipGeometry]. The
     * strip runs bottom-left, bottom-right, top-left, top-right of the (cropped) source; unit
     * coordinates have their origin at the source's bottom-left, as the texture matrices expect.
     */
    private fun quadFor(
        clip: RenderClip,
        transform: Transform,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<FloatArray, FloatArray> {
        val frameSize = FrameSize(timeline.width, timeline.height)
        // In a mosaic cell (#17), the cell is the clip's frame.
        val origin = Cells.origin(frameSize, clip.cell)
        val corners = ClipGeometry.corners(
            transform,
            Cells.size(frameSize, clip.cell),
            FrameSize(sourceWidth, sourceHeight),
        ).map { it.copy(x = it.x + origin.x, y = it.y + origin.y) }
        val position = FloatArray(16)
        listOf(corners[2], corners[3], corners[0], corners[1]).forEachIndexed { i, corner ->
            position[i * 4] = corner.x / timeline.width * 2 - 1
            position[i * 4 + 1] = 1 - corner.y / timeline.height * 2
            position[i * 4 + 3] = 1f
        }
        val left = transform.cropLeft
        val right = 1 - transform.cropRight
        val bottom = transform.cropBottom
        val top = 1 - transform.cropTop
        val unit = floatArrayOf(
            left, bottom, 0f, 1f,
            right, bottom, 0f, 1f,
            left, top, 0f, 1f,
            right, top, 0f, 1f,
        )
        return position to unit
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
        val FULL_UNIT: FloatArray = GlUtil.getTextureCoordinateBounds()

        /** The blur behind a cut-out person (#50), as a share of the picture's short side. */
        const val BEHIND_BLUR = 0.03f
    }
}
