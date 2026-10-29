package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import tech.idct.clipra.core.model.Background
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
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
            val clips = layer.clips.mapNotNull { clip -> renderClip(project, clip, media) }
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

    private fun renderClip(project: Project, clip: Clip, media: MediaResolver): RenderClip? {
        val ref = clip.source ?: return null
        val asset = project.asset(ref.mediaId) ?: return null
        val source = when (clip.type) {
            ClipType.Video -> media.source(asset)?.let {
                val length = asset.durationUs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE
                RenderSource.Video(it, ref.inPointUs, length)
            }

            ClipType.Audio -> media.source(asset)?.let { RenderSource.Audio(it, ref.inPointUs) }

            ClipType.Photo -> media.image(asset)?.let { RenderSource.Image(it) }

            // Text, captions and adjustment clips come with M4, M6 and M2.
            else -> null
        } ?: return null
        return RenderClip(
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
        )
    }

    /** ARGB of the project background. Blur falls back to black until it is built. */
    fun backgroundColor(background: Background): Int = when (background) {
        Background.Black, Background.Blur -> 0xFF000000.toInt()
        Background.White -> 0xFFFFFFFF.toInt()
        Background.Yellow -> 0xFFFFD23F.toInt()
    }
}
