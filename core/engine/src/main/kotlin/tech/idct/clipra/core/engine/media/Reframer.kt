package tech.idct.clipra.core.engine.media

import android.content.Context
import android.graphics.Bitmap
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.engine.render.TextRenderer
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.FaceBox
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Point
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Reframe
import tech.idct.clipra.core.model.Stabilizing
import tech.idct.clipra.core.model.mediaTimeAt
import tech.idct.clipra.ml.vision.FaceDetector
import tech.idct.clipra.ml.vision.SelfieSegmenter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Auto-reframe (#53) on the device: where the subject is along each shot that gets a crop path.
 * Face follows the face as Follow face finds it (#52); Subject follows the people that person
 * cutout finds (#50), by the middle of their mask, and makes those masks first where they aren't
 * made. On a steadied clip (#54) both are where the steadied picture shows them. Nothing leaves
 * the device.
 */
@Singleton
class Reframer @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
    private val faces: FaceTracks,
    private val cutouts: Cutouts,
) {
    /**
     * For each clip of [project] that gets a crop path, where its subject is ten times a second
     * (clip time, fractions of its picture; null where none was seen). Fixed follows nothing.
     * [onProgress] gets 0 to 1 over all the clips.
     */
    suspend fun track(
        project: Project,
        follow: Reframe.Follow,
        onProgress: (Float) -> Unit = {},
    ): Map<String, List<Pair<Long, Point?>>> = withContext(Dispatchers.Default) {
        if (follow == Reframe.Follow.Fixed) return@withContext emptyMap()
        val clips = project.layers.flatMap { it.clips }.filter(Reframe::follows)
        val found = HashMap<String, List<Pair<Long, Point?>>>()
        clips.forEachIndexed { index, clip ->
            ensureActive()
            val asset = clip.source?.let { project.asset(it.mediaId) } ?: return@forEachIndexed
            val share = { done: Float -> onProgress((index + done) / clips.size) }
            val samples = when (follow) {
                Reframe.Follow.Face -> faces.find(asset, clip, share) { !isActive }
                    ?.map { it.timeUs to it.box?.let { box -> Point(box.x, box.y) } }

                else -> people(asset, clip, share)
            }
            if (samples != null) found[clip.id] = samples
        }
        onProgress(1f)
        found
    }

    /** Where the people are along [clip], by the middle of their masks. */
    private suspend fun people(
        asset: MediaAsset,
        clip: Clip,
        onProgress: (Float) -> Unit,
    ): List<Pair<Long, Point?>>? = coroutineScope {
        // Making the masks is most of the work; their progress shows meanwhile.
        val watch = launch {
            cutouts.progress.first { progress ->
                progress[asset.id]?.let { onProgress(it * MASKS_SHARE) }
                false
            }
        }
        val made = cutouts.prepare(asset) { !isActive }
        watch.cancel()
        if (!made) return@coroutineScope null
        // Shared with the preview and the export, so it stays open.
        val masks = cutouts.masks(asset) ?: return@coroutineScope null
        val times = if (asset.kind == MediaKind.Photo) {
            listOf(0L)
        } else {
            (0 until clip.durationUs step Reframe.SAMPLE_US).toList()
        }
        times.map { timeUs -> timeUs to peopleAt(asset, clip, masks, timeUs) }
    }

    /**
     * Where the people of [clip] are at [timeUs] (clip time) by its finished masks, in fractions
     * of its picture as shown (steadied, #54); null when there is hardly anyone.
     */
    private fun peopleAt(asset: MediaAsset, clip: Clip, masks: CutoutMasks, timeUs: Long): Point? {
        val mediaUs = clip.mediaTimeAt(timeUs) ?: 0
        val mask = ByteArray(CutoutMasks.SIZE * CutoutMasks.SIZE)
        masks.frameAt(mediaUs, mask)
        val middle = middle(mask) ?: return null
        val motion = Stabilizer.cached(cache.stabilize(asset.id)) ?: return middle
        val steadied = motion.over(clip) ?: return middle
        val box = Stabilizing.steady(
            FaceBox(middle.x, middle.y, 0f, 0f),
            steadied.at(mediaUs),
            steadied.zoom,
            motion.aspect,
        )
        return Point(box.x, box.y)
    }

    /**
     * Where the people are along [clip], as [track] finds them, when their masks are already
     * made; null otherwise. The board's preview takes this first, so it shows what the copy
     * will do.
     */
    fun cachedPeople(project: Project, clip: Clip): List<Pair<Long, Point?>>? {
        val asset = clip.source?.let { project.asset(it.mediaId) } ?: return null
        val masks = cutouts.masks(asset) ?: return null
        val times = if (asset.kind == MediaKind.Photo) {
            listOf(0L)
        } else {
            (0 until clip.durationUs step Reframe.SAMPLE_US).toList()
        }
        return times.map { it to peopleAt(asset, clip, masks, it) }
    }

    /** What the board's preview follows in a frame: where, and for a face its box. */
    data class Sighting(val at: Point, val face: Reframe.Box? = null)

    /**
     * Where the subject is in one finished [frame] (fractions of it), for the board's preview:
     * the largest face, or the middle of the people. Null when there is none, or for Fixed.
     */
    suspend fun subjectIn(frame: Bitmap, follow: Reframe.Follow): Sighting? =
        withContext(Dispatchers.Default) {
            when (follow) {
                Reframe.Follow.Fixed -> null

                Reframe.Follow.Face -> FaceDetector(context).use { detector ->
                    detector.detect(frame).maxByOrNull { it.box.width() * it.box.height() }
                        ?.box
                        ?.let {
                            Sighting(
                                Point(it.centerX(), it.centerY()),
                                Reframe.Box(it.left, it.top, it.right, it.bottom),
                            )
                        }
                }

                Reframe.Follow.Subject -> SelfieSegmenter(context).use { segmenter ->
                    val side = SelfieSegmenter.SIZE
                    val small = Bitmap.createScaledBitmap(frame, side, side, true)
                    val pixels = IntArray(side * side)
                    small.getPixels(pixels, 0, side, 0, 0, side, side)
                    if (small !== frame) small.recycle()
                    val confidence = FloatArray(side * side)
                    segmenter.segment(pixels, confidence)
                    middle(ByteArray(side * side) { (confidence[it] * 255).toInt().toByte() })
                        ?.let { Sighting(it) }
                }
            }
        }

    /**
     * Where each title of [project] shows in a frame of [ratio] before its transform, for
     * keeping titles inside (#53). Text cards fill the frame, so they have none.
     */
    fun titleBoxes(project: Project, ratio: AspectRatio): Map<String, Reframe.Box> {
        val frame = project.format.copy(ratio = ratio).outputSize
        return project.layers.flatMap { it.clips }
            .filter { it.type == ClipType.Text }
            .mapNotNull { clip ->
                val spec = clip.text ?: return@mapNotNull null
                TextRenderer.box(context, spec, frame.width, frame.height)?.let {
                    clip.id to Reframe.Box(it.left, it.top, it.right, it.bottom)
                }
            }
            .toMap()
    }

    companion object {
        /** Making the masks is about this share of following the people in a clip. */
        private const val MASKS_SHARE = 0.9f

        /** The share of a mask (0 to 255 per cell) that has to be a person to count. */
        private const val PERSON = 128

        /** Less of the picture than this (5 %) is nobody to follow, such as a hand in a corner. */
        private const val FEWEST_SHARE = 0.05f

        /**
         * The middle of the person in [mask] (a square grid of confidences, 0 to 255), in
         * fractions of the picture; null when there is hardly anyone.
         */
        fun middle(mask: ByteArray): Point? {
            val side = sqrt(mask.size.toDouble()).toInt()
            var x = 0L
            var y = 0L
            var count = 0
            for (row in 0 until side) {
                for (column in 0 until side) {
                    if ((mask[row * side + column].toInt() and 0xFF) < PERSON) continue
                    x += column
                    y += row
                    count++
                }
            }
            if (count < mask.size * FEWEST_SHARE) return null
            return Point((x.toFloat() / count + 0.5f) / side, (y.toFloat() / count + 0.5f) / side)
        }
    }
}
