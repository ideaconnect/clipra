package tech.idct.clipra.core.engine.preview

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.transformer.CompositionPlayer
import tech.idct.clipra.core.engine.media.CameraMotion
import tech.idct.clipra.core.engine.media.CutoutMasks
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.render.CompositionFactory
import tech.idct.clipra.core.engine.render.LiveEdits
import tech.idct.clipra.core.engine.render.MediaResolver
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.ColorGrade
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.VoiceCleanup
import kotlin.math.roundToInt

/**
 * The editor's preview (#14): the project's composition, built by the same layer compositor as
 * the export, at preview size from proxies, played by Media3's CompositionPlayer. Use it on the
 * main thread.
 */
@OptIn(UnstableApi::class, ExperimentalApi::class)
class PreviewPlayer(private val context: Context, cache: MediaCache) {
    private val player = CompositionPlayer.Builder(context).build()
    private val media = CachingResolver(ProjectMediaResolver(context, cache, false, IMAGE_SIDE))

    /** CompositionPlayer can't seek or play before it has a composition. */
    private var hasComposition = false

    /** The current composition's gesture values; each rebuild starts without any. */
    private var live = LiveEdits()

    /** The project shown, to rebuild when the preview comes back on screen. */
    private var project: Project? = null
    private var detached = false

    /** The Color board's before and after (#28), kept across rebuilds. */
    private val splits = HashMap<String, Float>()

    /** Called when playback reaches the end. */
    var onEnded: (() -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) onEnded?.invoke()
            }
        })
    }

    val positionUs: Long get() = player.currentPosition * 1_000

    val isPlaying: Boolean get() = player.playWhenReady

    /** Rebuilds the composition after an edit and returns to [positionUs]. */
    fun setProject(project: Project, positionUs: Long) {
        val size = previewSize(project.format)
        val timeline = ProjectRender.timeline(project, media, size.width, size.height)
        this.project = project
        live = LiveEdits()
        splits.forEach { (clipId, split) -> live.setSplit(clipId, split) }
        player.setComposition(CompositionFactory.create(context, timeline, live = live))
        hasComposition = true
        player.seekTo(positionUs / 1_000)
        player.prepare()
    }

    /**
     * Shows the preview on [surface], drawing the paused frame on it. A surface that comes back
     * after [detach] gets the composition rebuilt where it was: a paused frame isn't drawn again
     * on it otherwise.
     */
    fun attach(surface: Surface, width: Int, height: Int) {
        if (released) return
        this.surface = surface
        player.setVideoSurface(surface, Size(width, height))
        if (detached) project?.let { setProject(it, positionUs) } else redraw()
        detached = false
    }

    fun detach() {
        // The screen may release the player before its view lets go of the surface.
        if (released) return
        detached = true
        // CompositionPlayer clears only the surface it was given.
        surface?.let(player::clearVideoSurface)
        surface = null
    }

    fun play() {
        if (hasComposition) player.playWhenReady = true
    }

    fun pause() {
        player.playWhenReady = false
    }

    /** Plays the sound at [volume], 0 to 1: silent while a voiceover is recorded (#41). */
    fun setVolume(volume: Float) {
        player.volume = volume
    }

    fun seekTo(positionUs: Long) {
        if (hasComposition) player.seekTo(positionUs / 1_000)
    }

    /**
     * Shows [clipId] with [transform] while a gesture is going, without an edit: the paused
     * frame is drawn again. The next [setProject] (the committed edit) replaces it.
     */
    fun setLiveTransform(clipId: String, transform: Transform) {
        live.setTransform(clipId, transform)
        redraw()
    }

    /** Like [setLiveTransform], for the filter strength and hold-to-compare; null restores. */
    fun setLiveEffects(clipId: String, effects: List<Effect>?) {
        live.setEffects(clipId, effects)
        redraw()
    }

    /** Like [setLiveTransform], for a mask being dragged. */
    fun setLiveMask(clipId: String, mask: Mask) {
        live.setMask(clipId, mask)
        redraw()
    }

    /**
     * Like [setLiveTransform], for the Color board (#28): [grade] while a slider moves or a
     * LUT is tried; null goes back to the clip's own.
     */
    fun setLiveColor(clipId: String, grade: ColorGrade?) {
        live.setColor(clipId, grade?.let { LiveEdits.LiveColor(it, it.lut?.let(media::lut)) })
        redraw()
    }

    /** Shows [clipId] with [cutout]'s settings while a slider moves (#50); null goes back. */
    fun setLiveCutout(clipId: String, cutout: Cutout?) {
        live.setCutout(clipId, cutout)
        redraw()
    }

    /** Shows [clipId] ungraded left of [split] (a share of its width); null shows it all graded. */
    fun setColorSplit(clipId: String, split: Float?) {
        if (split == null) splits.remove(clipId) else splits[clipId] = split
        live.setSplit(clipId, split)
        redraw()
    }

    /** Like [setLiveTransform], for the opacity slider. */
    fun setLiveOpacity(clipId: String, opacity: Float) {
        live.setOpacity(clipId, opacity)
        redraw()
    }

    private fun redraw() {
        if (!hasComposition || player.playWhenReady) return
        // A seek to where the player already is draws nothing new: step a millisecond either
        // way, back and forth, which stays on the same frame.
        val position = player.currentPosition
        val back = nudgedForward || position + NUDGE_MS > player.duration
        val target = if (back && position >= NUDGE_MS) position - NUDGE_MS else position + NUDGE_MS
        nudgedForward = target > position
        player.seekTo(target)
    }

    /** Whether the last redraw stepped forward, so the next steps back. */
    private var nudgedForward = false

    fun release() {
        released = true
        player.release()
    }

    private var released = false

    /** The surface the preview is on. */
    private var surface: Surface? = null

    /**
     * Photos decode once per editor session instead of on every rebuild, and text draws again
     * only when it changes.
     */
    private class CachingResolver(private val inner: MediaResolver) : MediaResolver {
        private val images = HashMap<String, Bitmap?>()
        private val texts = object : LinkedHashMap<TextKey, Bitmap?>(16, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<TextKey, Bitmap?>) =
                size > MAX_TEXTS
        }

        private data class TextKey(val spec: TextSpec, val card: Boolean, val w: Int, val h: Int)

        override fun text(spec: TextSpec, card: Boolean, width: Int, height: Int): Bitmap? =
            texts.getOrPut(TextKey(spec, card, width, height)) {
                inner.text(spec, card, width, height)
            }

        override fun source(asset: MediaAsset): String? = inner.source(asset)

        override fun voice(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?): String? =
            inner.voice(asset, cleanup, noise)

        override fun caption(caption: CaptionSpec, current: Int, width: Int, height: Int) =
            inner.caption(caption, current, width, height)

        override fun image(asset: MediaAsset): Bitmap? = images.getOrPut(asset.id) {
            inner.image(asset)
        }

        // Each moment of a text animation (#33) is drawn once, when it shows.
        override fun animatedText(
            spec: TextSpec,
            card: Boolean,
            width: Int,
            height: Int,
            motion: Animations.TextMotion,
        ): Bitmap? = inner.animatedText(spec, card, width, height, motion)

        private val luts = HashMap<String, CubeLut?>()

        override fun speech(asset: MediaAsset): List<LongRange>? = inner.speech(asset)

        override fun cutout(asset: MediaAsset): CutoutMasks? = inner.cutout(asset)

        override fun cameraMotion(asset: MediaAsset): CameraMotion? = inner.cameraMotion(asset)

        override fun lut(id: String): CubeLut? = luts.getOrPut(id) { inner.lut(id) }
    }

    companion object {
        /** Preview renders at 540p on its short side: proxies are 720p, and the view is small. */
        const val PREVIEW_SHORT_SIDE = 540
        private const val IMAGE_SIDE = PREVIEW_SHORT_SIDE * 2
        private const val MAX_TEXTS = 32
        private const val NUDGE_MS = 1L

        fun previewSize(format: Format): tech.idct.clipra.core.model.Size {
            val output = format.outputSize
            val scale = PREVIEW_SHORT_SIDE.toFloat() / minOf(output.width, output.height)
            fun even(v: Float) = (v / 2).roundToInt() * 2
            return tech.idct.clipra.core.model.Size(
                even(output.width * scale),
                even(output.height * scale),
            )
        }
    }
}
