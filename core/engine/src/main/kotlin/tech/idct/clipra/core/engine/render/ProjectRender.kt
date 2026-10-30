package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import tech.idct.clipra.core.model.Background
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.cuts

/**
 * Where the engine reads a project's media: proxies for preview, originals for export. Returns
 * null for media that is missing (moved or deleted); its clips are then left out.
 */
interface MediaResolver {
    /** A file path or content URI for a video or sound asset. */
    fun source(asset: MediaAsset): String?

    /** The decoded picture of a photo asset. */
    fun image(asset: MediaAsset): Bitmap?

    /** A text clip drawn at [width] × [height] (#31); a [card] fills it with its colour. */
    fun text(spec: TextSpec, card: Boolean, width: Int, height: Int): Bitmap?

    /** The sound of [asset] after AI voice cleanup (#39), once it is ready; null until then. */
    fun voice(asset: MediaAsset, cleanup: VoiceCleanup): String? = null

    /** A caption (#45) at [width] × [height] with word [current] spoken (-1: none). */
    fun caption(caption: CaptionSpec, current: Int, width: Int, height: Int): Bitmap? = null
}

/** Turns a project into what the layer compositor draws (docs/architecture.md, section 4). */
object ProjectRender {
    /**
     * The render timeline of [project] at [width] × [height] (the output size for export, a
     * smaller size for preview). Layers keep their order, L1 at the bottom; hidden layers draw
     * nothing and muted layers add no sound.
     */
    fun timeline(
        project: Project,
        media: MediaResolver,
        width: Int = project.format.outputSize.width,
        height: Int = project.format.outputSize.height,
        frameRate: Int = project.format.frameRate.fps,
    ): RenderTimeline = RenderTimeline(
        width = width,
        height = height,
        frameRate = frameRate,
        // At least one frame, so an empty project still renders its background.
        durationUs = maxOf(project.durationUs, 1_000_000L / frameRate),
        background = backgroundColor(project.format.background),
        layers = project.layers.map { layer ->
            val clips = layer.clips.flatMap { clip ->
                if (clip.type == ClipType.Caption) {
                    captionClips(clip, media, width, height)
                } else {
                    listOfNotNull(renderClip(project, clip, media, width, height))
                }
            }
            RenderLayer(
                clips = clips,
                hidden = layer.hidden,
                muted = layer.muted,
                transitions = transitions(project, layer.id, clips.map { it.id }.toSet()),
            )
        },
    )

    /** The layer's transitions whose cut is still there, between clips that render. */
    private fun transitions(project: Project, layerId: String, rendered: Set<String>) =
        project.transitions.filter { it.layerId == layerId }.mapNotNull { transition ->
            val cut = project.cuts().firstOrNull {
                it.layerId == layerId && it.timeUs == transition.cutTimeUs
            } ?: return@mapNotNull null
            if (cut.from.id !in rendered || cut.to.id !in rendered) return@mapNotNull null
            RenderTransition(
                fromClipId = cut.from.id,
                toClipId = cut.to.id,
                startUs = cut.timeUs - transition.durationUs / 2,
                durationUs = transition.durationUs,
                type = transition.type,
                easing = transition.easing,
            )
        }

    /**
     * A caption (#45) as one render clip per spoken word, each drawn when it first shows, so the
     * highlight follows the speech; without a highlight, one for the whole caption.
     */
    private fun captionClips(
        clip: Clip,
        media: MediaResolver,
        width: Int,
        height: Int,
    ): List<RenderClip> {
        val caption = clip.caption ?: return emptyList()
        val drawn = { current: Int ->
            RenderSource.Drawn {
                media.caption(caption, current, width, height)
                    ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            }
        }
        val highlights = caption.highlight && caption.style != CaptionStyle.Minimal
        if (!highlights || caption.words.isEmpty()) {
            return listOf(renderClipOf(clip, drawn(-1)))
        }
        val starts = caption.words.map { it.startUs.coerceIn(0, clip.durationUs) }
        val states = buildList {
            if (starts.first() > 0) add(Triple(-1, 0L, starts.first()))
            starts.forEachIndexed { i, start ->
                val end = starts.getOrNull(i + 1) ?: clip.durationUs
                if (end > start) add(Triple(i, start, end))
            }
        }
        return states.map { (current, from, to) ->
            renderClipOf(clip, drawn(current)).copy(
                id = "${clip.id}#$current",
                startUs = clip.startUs + from,
                durationUs = to - from,
            )
        }
    }

    private fun renderClip(
        project: Project,
        clip: Clip,
        media: MediaResolver,
        width: Int,
        height: Int,
    ): RenderClip? {
        if (clip.type == ClipType.TextCard || clip.type == ClipType.Text) {
            val spec = clip.text ?: return null
            // Drawn at the frame's size, larger when scaled up, so it stays sharp.
            val factor = clip.transform.value.scale.coerceIn(1f, MAX_TEXT_FACTOR)
            val bitmap = media.text(
                spec,
                card = clip.type == ClipType.TextCard,
                width = (width * factor).toInt(),
                height = (height * factor).toInt(),
            ) ?: return null
            return renderClipOf(clip, RenderSource.Image(bitmap))
        }
        val ref = clip.source ?: return null
        val asset = project.asset(ref.mediaId) ?: return null
        val source = when (clip.type) {
            ClipType.Video -> media.source(asset)?.let {
                val length = asset.durationUs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE
                RenderSource.Video(it, ref.inPointUs, length, asset.hasAudio)
            }

            ClipType.Audio -> media.source(asset)?.let { RenderSource.Audio(it, ref.inPointUs) }

            ClipType.Photo -> media.image(asset)?.let { RenderSource.Image(it) }

            // Captions and adjustment clips come with M6 and M2.
            else -> null
        } ?: return null
        val voice = clip.audio.voiceCleanup?.let { media.voice(asset, it) }
        return renderClipOf(clip, source, voice)
    }

    private fun renderClipOf(clip: Clip, source: RenderSource, voice: String? = null) = RenderClip(
        id = clip.id,
        startUs = clip.startUs,
        durationUs = clip.durationUs,
        source = source,
        transform = clip.transform.value,
        blend = clip.blend,
        opacity = clip.opacity.value,
        rate = clip.speed.rate,
        effects = clip.effects,
        mask = clip.mask,
        audio = clip.audio,
        voicePath = voice,
    )

    /** Text renders up to twice the frame's size when scaled up. */
    private const val MAX_TEXT_FACTOR = 2f

    /** ARGB of the project background. Blur falls back to black until it is built. */
    fun backgroundColor(background: Background): Int = when (background) {
        Background.Black, Background.Blur -> 0xFF000000.toInt()
        Background.White -> 0xFFFFFFFF.toInt()
        Background.Yellow -> 0xFFFFD23F.toInt()
    }
}
