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
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.CellRect
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.Transitions
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
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
    private val bitmapTextures = HashMap<String, Int>()
    private val drawnSizes = HashMap<String, Pair<Int, Int>>()

    private val externalProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP_EXTERNAL)
    private val bitmapProgram = GlProgram(Shaders.VERTEX, Shaders.CLIP_2D)
    private val blendProgram = GlProgram(Shaders.VERTEX, Shaders.BLEND)
    private val copyProgram = GlProgram(Shaders.VERTEX, Shaders.COPY)
    private val filterProgram = GlProgram(Shaders.VERTEX, Shaders.FILTER)
    private val blurProgram = GlProgram(Shaders.VERTEX, Shaders.BLUR)
    private val transitionProgram = GlProgram(Shaders.VERTEX, Shaders.TRANSITION)

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
                drawClip(clip, presentationTimeUs)
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
        bitmapTextures.values.forEach { GlUtil.deleteTexture(it) }
        bitmapTextures.clear()
        (canvas + clipTarget).filterNotNull().forEach { it.delete() }
        transitionTargets?.forEach { it.delete() }
        transitionTargets = null
        effectTargets.values.forEach { pair -> pair.forEach { it.delete() } }
        effectTargets.clear()
        val programs = listOf(
            externalProgram,
            bitmapProgram,
            blendProgram,
            copyProgram,
            filterProgram,
            blurProgram,
            transitionProgram,
        )
        programs.forEach { it.delete() }
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
        // Keyframed values at this frame (#21); a gesture in progress shows its own.
        val opacity = clip.opacityAt(timeUs, live?.opacity(clip.id) ?: clip.keyedOpacityAt(timeUs))
        if (opacity <= 0f) return null
        val filters = (live?.effects(clip.id) ?: clip.effects).mapNotNull { effect ->
            FilterRecipes.of(effect.type)?.takeIf { effect.strength > 0f }?.let { it to effect }
        }
        val mask = live?.mask(clip.id) ?: clip.maskAt(timeUs)
        val frame = if (filters.isEmpty()) raw else filtered(raw, filters, mask, timeUs)
        val (position, unit) = quadFor(clip, timeUs, frame.width, frame.height)
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

    /**
     * The clip's picture through [filters]: drawn upright into an effect texture (no larger than
     * the frame), then one pass per filter, two for a blur. The [mask] limits one of them.
     */
    private fun filtered(
        frame: ClipFrame,
        filters: List<Pair<FilterRecipe, Effect>>,
        mask: Mask?,
        timeUs: Long,
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
        drawSource(source, frame, 1f, FULL_FRAME, FULL_UNIT)

        // The mask limits the effect it names, or the first filter.
        val maskedType = mask?.takeIf { it.shape != MaskShape.None }
            ?.let { it.effect ?: filters.first().second.type }

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
        val result = targets[current]
        return ClipFrame(result.texture, identity, width, height, external = false)
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
            is RenderSource.Audio -> null
        }

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
        return ClipFrame(video.textureId, video.textureMatrix, video.width, video.height, true)
    }

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
        videoSources.keys.filter { it !in active }.forEach { videoSources.remove(it)?.release() }
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
        timeUs: Long,
        sourceWidth: Int,
        sourceHeight: Int,
    ): Pair<FloatArray, FloatArray> {
        val transform = live?.transform(clip.id) ?: clip.transformAt(timeUs)
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
    }
}
