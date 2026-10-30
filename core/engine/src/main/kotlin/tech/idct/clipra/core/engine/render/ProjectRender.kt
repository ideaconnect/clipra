package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import tech.idct.clipra.core.engine.media.CameraMotion
import tech.idct.clipra.core.engine.media.CutoutMasks
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.Background
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Cells
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.CubeLut
import tech.idct.clipra.core.model.Luts
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.cuts
import tech.idct.clipra.core.model.timelineAt

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
    fun voice(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?): String? = null

    /** A caption (#45) at [width] × [height] with word [current] spoken (-1: none). */
    fun caption(caption: CaptionSpec, current: Int, width: Int, height: Int): Bitmap? = null

    /** Where [asset] speaks (#40), in microseconds of the media, once that is known. */
    fun speech(asset: MediaAsset): List<LongRange>? = null

    /** The person masks of [asset] (#50), once they are made. */
    fun cutout(asset: MediaAsset): CutoutMasks? = null

    /** The camera motion in [asset] (#54), once it is analysed. */
    fun cameraMotion(asset: MediaAsset): CameraMotion? = null

    /** The LUT with [id] (#28): a built-in one, or an imported `.cube` file; null if gone. */
    fun lut(id: String): CubeLut? = Luts.builtIn(id)

    /** Text [spec] with its letters in [motion] (#33); still text when it can't animate. */
    fun animatedText(
        spec: TextSpec,
        card: Boolean,
        width: Int,
        height: Int,
        motion: Animations.TextMotion,
    ): Bitmap? = text(spec, card, width, height)
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
    ): RenderTimeline = voicesOf(project, media).let { voices ->
        RenderTimeline(
            width = width,
            height = height,
            frameRate = frameRate,
            // At least one frame, so an empty project still renders its background.
            durationUs = maxOf(project.durationUs, 1_000_000L / frameRate),
            background = backgroundColor(project.format.background),
            layers = project.layers.map { layer ->
                val clips = layer.clips.flatMap { clip ->
                    val rendered = if (clip.type == ClipType.Caption) {
                        captionClips(clip, media, width, height)
                    } else if (clip.animatesText()) {
                        animatedTextClips(project, clip, media, width, height, frameRate)
                    } else {
                        listOfNotNull(renderClip(project, clip, media, width, height))
                    }
                    // A clip in a mosaic cell is laid out in it and cut to it (#17).
                    val cell = Cells.rect(project.format, clip.cell)
                    // Its grade's LUT (#28), read once.
                    val lut = clip.color?.lut?.let(media::lut)
                    // The voices it ducks under (#40).
                    val ducks = ducksFor(project, clip, voices)
                    if (cell == null && lut == null && ducks.isEmpty()) {
                        rendered
                    } else {
                        rendered.map { it.copy(cell = cell, lut = lut, ducks = ducks) }
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
    }

    /** A voice that ducks the others (#40): its speech on the timeline. */
    private class Voice(
        val clipId: String,
        val mediaId: String,
        val speech: List<LongRange>,
        val depthDb: Float,
    )

    /** Every clip set to duck the others, with its speech mapped onto the timeline. */
    private fun voicesOf(project: Project, media: MediaResolver): List<Voice> =
        project.layers.filter { !it.muted }.flatMap { it.clips }.mapNotNull { clip ->
            val depth = clip.audio.ducking ?: return@mapNotNull null
            val ref = clip.source ?: return@mapNotNull null
            val asset = project.asset(ref.mediaId) ?: return@mapNotNull null
            val speech = media.speech(asset) ?: return@mapNotNull null
            val onTimeline = speech.mapNotNull { range ->
                val a = clip.timelineAt(range.first) ?: return@mapNotNull null
                val b = clip.timelineAt(range.last) ?: return@mapNotNull null
                val from = maxOf(minOf(a, b), clip.startUs)
                val to = minOf(maxOf(a, b), clip.endUs)
                if (to > from) from..to else null
            }
            Voice(clip.id, ref.mediaId, onTimeline, depth)
        }

    /** The voices [clip] ducks under: every other one, not of its own media, in its time. */
    private fun ducksFor(project: Project, clip: Clip, voices: List<Voice>): List<RenderDuck> {
        if (clip.type != ClipType.Audio && clip.type != ClipType.Video) return emptyList()
        return voices.filter { it.clipId != clip.id && it.mediaId != clip.source?.mediaId }
            .mapNotNull { voice ->
                val local = voice.speech.filter {
                    it.last >= clip.startUs && it.first <= clip.endUs
                }
                    .map { (it.first - clip.startUs)..(it.last - clip.startUs) }
                if (local.isEmpty()) null else RenderDuck(local, voice.depthDb)
            }
    }

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

    /**
     * A text clip with an animation in or out (#33): a piece per frame while its letters move,
     * each drawn by the text renderer at that moment (only when on screen), and the still text
     * between. Pieces keep the clip's time base, so its keyframes and transform carry on.
     */
    private fun animatedTextClips(
        project: Project,
        clip: Clip,
        media: MediaResolver,
        width: Int,
        height: Int,
        frameRate: Int,
    ): List<RenderClip> {
        val still = renderClip(project, clip, media, width, height) ?: return emptyList()
        val spec = clip.text ?: return listOf(still)
        val animation = clip.animation ?: return listOf(still)
        val card = clip.type == ClipType.TextCard
        val length = Animations.lengthUs(clip.durationUs)
        // As large as the still text is drawn (larger when scaled up).
        val drawn = (still.source as? RenderSource.Image)?.bitmap
        val factor = drawn?.let { it.width.toFloat() / width } ?: 1f

        // One piece per frame of the timeline's grid, at that frame's moment.
        fun frameAt(k: Long) = k * 1_000_000L / frameRate
        fun pieces(from: Long, to: Long, tag: String): List<RenderClip> {
            val starts = buildList {
                add(clip.startUs + from)
                var k = (clip.startUs + from) * frameRate / 1_000_000L + 1
                while (frameAt(k) < clip.startUs + to) add(frameAt(k++))
            }
            return starts.mapIndexed { i, start ->
                val end = starts.getOrNull(i + 1) ?: (clip.startUs + to)
                val motion = Animations.textMotion(animation, start - clip.startUs, clip.durationUs)
                val source = if (motion == null) {
                    still.source
                } else {
                    RenderSource.Drawn {
                        media.animatedText(
                            spec,
                            card,
                            (width * factor).toInt(),
                            (height * factor).toInt(),
                            motion,
                        ) ?: Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
                    }
                }
                still.copy(
                    id = "${clip.id}#$tag$i",
                    startUs = start,
                    durationUs = end - start,
                    source = source,
                )
            }
        }
        val enterEnd = if (animation.enter != null) length else 0L
        val exitStart = if (animation.exit != null) clip.durationUs - length else clip.durationUs
        return buildList {
            if (enterEnd > 0) addAll(pieces(0, enterEnd, "in"))
            if (exitStart > enterEnd) {
                val between = exitStart - enterEnd
                add(still.copy(startUs = clip.startUs + enterEnd, durationUs = between))
            }
            if (exitStart < clip.durationUs) {
                addAll(pieces(maxOf(exitStart, enterEnd), clip.durationUs, "out"))
            }
        }
    }

    private fun Clip.isText() = type == ClipType.Text || type == ClipType.TextCard

    /** A text clip with an animation in or out (#33), drawn a frame at a time. */
    private fun Clip.animatesText() = isText() && animation?.let { it.enter ?: it.exit } != null

    private fun renderClip(
        project: Project,
        clip: Clip,
        media: MediaResolver,
        width: Int,
        height: Int,
    ): RenderClip? {
        if (clip.type == ClipType.TextCard || clip.type == ClipType.Text) {
            val spec = clip.text ?: return null
            // Drawn at the frame's size, larger when scaled up (at its largest keyframe), so it
            // stays sharp.
            val largest = (
                clip.transform.keyframes.map { it.value.scale } +
                    clip.transform.value.scale
                ).max()
            val factor = largest.coerceIn(1f, MAX_TEXT_FACTOR)
            val bitmap = media.text(
                spec,
                card = clip.type == ClipType.TextCard,
                width = (width * factor).toInt(),
                height = (height * factor).toInt(),
            ) ?: return null
            return renderClipOf(clip, RenderSource.Image(bitmap))
        }
        // An adjustment clip (#23) grades and filters the layers below it.
        if (clip.type == ClipType.Adjustment) return renderClipOf(clip, RenderSource.Adjustment)
        val ref = clip.source ?: return null
        val asset = project.asset(ref.mediaId) ?: return null
        val source = when (clip.type) {
            ClipType.Video -> media.source(asset)?.let {
                val length = asset.durationUs.takeIf { d -> d > 0 } ?: Long.MAX_VALUE
                RenderSource.Video(it, ref.inPointUs, length, asset.hasAudio)
            }

            ClipType.Audio -> media.source(asset)?.let { RenderSource.Audio(it, ref.inPointUs) }

            ClipType.Photo -> media.image(asset)?.let { RenderSource.Image(it) }

            // Captions are drawn by captionClips; text and adjustments above.
            else -> null
        } ?: return null
        // Cleaned up (#39) and noise reduced (#38), once that is ready.
        val audio = clip.audio
        val voice = if (audio.voiceCleanup != null || audio.noiseReduction != null) {
            media.voice(asset, audio.voiceCleanup, audio.noiseReduction)
        } else {
            null
        }
        // A chroma key (#51) at once; people cut out (#50) once the masks are made, the whole
        // picture until then.
        val cutout = clip.cutout?.let { settings ->
            if (settings.key != null) {
                RenderCutout(settings, null)
            } else {
                media.cutout(asset)?.let { RenderCutout(settings, it) }
            }
        }
        // Steadied (#54) once the media is analysed, over the part the clip plays.
        val stabilized = clip.stabilize?.let { media.cameraMotion(asset)?.over(clip) }
        return renderClipOf(clip, source, voice, cutout).copy(stabilized = stabilized)
    }

    private fun renderClipOf(
        clip: Clip,
        source: RenderSource,
        voice: String? = null,
        cutout: RenderCutout? = null,
    ) = RenderClip(
        id = clip.id,
        startUs = clip.startUs,
        durationUs = clip.durationUs,
        source = source,
        transform = clip.transform.value,
        blend = clip.blend,
        opacity = clip.opacity.value,
        transformKeyframes = clip.transform.keyframes,
        opacityKeyframes = clip.opacity.keyframes,
        maskKeyframes = clip.maskKeyframes,
        speed = clip.speed,
        color = clip.color,
        // Text animates in its renderer, not as a whole (#33).
        animation = clip.animation.takeIf { !clip.isText() },
        sourceSpanUs = clip.source?.let { it.outPointUs - it.inPointUs } ?: 0,
        effects = clip.effects,
        mask = clip.mask,
        audio = clip.audio,
        voicePath = voice,
        cutout = cutout,
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
