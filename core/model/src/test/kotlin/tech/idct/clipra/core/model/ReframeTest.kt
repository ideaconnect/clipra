package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Auto-reframe (#53): the crop path, the formats offered, and titles kept inside. */
class ReframeTest {
    private val sec = 1_000_000L
    private val hd = Size(1920, 1080)
    private val tall = Size(1080, 1920)

    private val video = MediaAsset(
        "tram",
        "content://tram",
        MediaKind.Video,
        durationUs = 10 * sec,
        width = 1920,
        height = 1080,
        hasAudio = true,
    )

    private fun samples(vararg points: Point?) =
        points.mapIndexed { i, p -> i * Reframe.SAMPLE_US to p }

    @Test
    fun theBoardsFormatsAreOfferedButNeverTheProjectsOwn() {
        val wide = Project("p", "p", format = Format(ratio = AspectRatio.Landscape16x9))
        assertEquals(
            listOf(AspectRatio.Portrait9x16, AspectRatio.Square1x1, AspectRatio.Portrait4x5),
            Reframe.ratios(wide),
        )
        val shorts = Project("p", "p", format = Format(ratio = AspectRatio.Portrait9x16))
        assertEquals(
            listOf(AspectRatio.Square1x1, AspectRatio.Portrait4x5, AspectRatio.Landscape16x9),
            Reframe.ratios(shorts),
        )
    }

    @Test
    fun onlyClipsCoveringTheFrameGetACropPath() {
        val clip = Clip("v", ClipType.Video, 0, sec, MediaRef("tram", 0, sec))
        assertTrue(Reframe.follows(clip))
        assertTrue(Reframe.follows(clip.copy(type = ClipType.Photo)))
        // Picture in picture, turned, fitted, in a mosaic cell, keyframed: they keep their place.
        assertFalse(Reframe.follows(clip.copy(transform = Keyframed(Transform(scale = 0.5f)))))
        assertFalse(Reframe.follows(clip.copy(transform = Keyframed(Transform(rotation = 5f)))))
        assertFalse(Reframe.follows(clip.copy(transform = Keyframed(Transform(fit = Fit.Fit)))))
        assertFalse(Reframe.follows(clip.copy(cell = 1)))
        assertFalse(
            Reframe.follows(
                clip.copy(
                    transform = Keyframed(Transform(), listOf(Keyframe(0, Transform(x = 0.1f)))),
                ),
            ),
        )
        assertFalse(Reframe.follows(clip.copy(type = ClipType.Text, source = null)))
    }

    @Test
    fun gapsTakeTheNearestSightingAndNothingSeenIsNoPath() {
        val left = Point(0.2f, 0.5f)
        val right = Point(0.8f, 0.5f)
        // Quick, so the smoothing hardly reaches past a sample or two.
        val path = Reframe.path(samples(null, left, null, null, null, null, right, null), 100)!!
        assertEquals(8, path.size)
        // The jump between them tilts the line a little at the ends.
        assertEquals(0.2f, path[0].second.x, 0.025f)
        assertEquals(0.8f, path[7].second.x, 0.025f)
        assertEquals(700_000L, path[7].first)
        assertNull(Reframe.path(samples(null, null), 50))
    }

    @Test
    fun smoothCameraMotionMovesLater() {
        // The subject jumps from left to right after two seconds.
        val points = (0 until 40).map { if (it < 20) Point(0.3f, 0.5f) else Point(0.7f, 0.5f) }
        val quick = Reframe.path(samples(*points.toTypedArray()), 100)!!
        val smooth = Reframe.path(samples(*points.toTypedArray()), 0)!!
        // Half a second before the jump the quick crop is still on the left, the smooth one
        // already on its way.
        assertEquals(0.3f, quick[15].second.x, 0.01f)
        assertTrue(smooth[15].second.x > 0.4f)
        assertTrue(Reframe.sigmaUs(0) > Reframe.sigmaUs(Reframe.DEFAULT_MOTION))
        assertTrue(Reframe.sigmaUs(Reframe.DEFAULT_MOTION) > Reframe.sigmaUs(100))
    }

    @Test
    fun aSteadyMoveIsFollowedRightToTheEnds() {
        // Left to right over three seconds: even the smoothest camera is on it at the cut.
        val walk = (0..30).map { it * Reframe.SAMPLE_US to Point(0.2f + it * 0.02f, 0.5f) }
        val path = Reframe.path(walk, 0)!!
        assertEquals(0.2f, path.first().second.x, 0.005f)
        assertEquals(0.8f, path.last().second.x, 0.005f)
        assertEquals(0.5f, path[15].second.x, 0.005f)
    }

    @Test
    fun theLeashKeepsAJumpingSubjectInFrame() {
        val points = (0 until 40).map { if (it < 20) Point(0.3f, 0.5f) else Point(0.7f, 0.5f) }
        val leash = Point(0.08f, 0.5f)
        val path = Reframe.path(samples(*points.toTypedArray()), 0, leash)!!
        path.forEachIndexed { i, (_, p) ->
            assertTrue("at $i: ${p.x}", abs(p.x - points[i].x) <= 0.08f + 1e-4f)
        }
        // The window of a 16:9 picture in a 9:16 frame is 0.32 of it: 80 % of half is 0.13.
        assertEquals(0.1266f, Reframe.leash(Transform(), tall, hd).x, 0.001f)
        assertEquals(0.4f, Reframe.leash(Transform(), tall, hd).y, 0.001f)
    }

    @Test
    fun theCropKeepsTheSubjectInTheMiddleAsFarAsThePictureReaches() {
        // A 16:9 shot in a 9:16 frame is 3413 px wide: it can move 1.08 frames either way.
        val centred = Reframe.centreOn(Transform(), tall, hd, Point(0.75f, 0.5f))
        val at = ClipGeometry.toFrame(centred, tall, hd, 0.75f, 0.5f)
        assertEquals(540f, at.x, 1f)
        assertEquals(0f, centred.y, 0.0001f)
        // At the picture's edge the crop stops where the picture ends.
        val edge = Reframe.centreOn(Transform(), tall, hd, Point(1f, 0.5f))
        val corners = ClipGeometry.corners(edge, tall, hd)
        assertEquals(1080f, corners.maxOf { it.x }, 1f)
        // A face sits a little above the middle when the picture is taller than the frame.
        val portrait = Size(1080, 1920)
        val face = Reframe.centreOn(
            Transform(),
            Size(1080, 1080),
            portrait,
            Point(0.5f, 0.5f),
            Reframe.aim(Reframe.Follow.Face),
        )
        assertEquals(
            0.42f * 1080,
            ClipGeometry.toFrame(face, Size(1080, 1080), portrait, 0.5f, 0.5f).y,
            1f,
        )
    }

    @Test
    fun thePathBecomesFewKeyframes() {
        val clip = Clip("v", ClipType.Video, 0, 4 * sec, MediaRef("tram", 0, 4 * sec))
        // A steady pan from left to right: a start and an end are enough.
        val pan = (0..40).map { it * Reframe.SAMPLE_US to Point(0.3f + it * 0.01f, 0.5f) }
        val path = Reframe.cropPath(clip, tall, hd, pan, Point(0.5f, 0.5f))
        assertEquals(listOf(0L, 4 * sec), path.keyframes.map { it.timeUs })
        assertEquals(path.value, path.keyframes.first().value)
        assertEquals(Easing.Linear, path.keyframes.first().easing)
        // There and back: the turn gets a keyframe too.
        val back = (0..40).map {
            val f = if (it <= 20) it / 20f else (40 - it) / 20f
            it * Reframe.SAMPLE_US to Point(0.3f + 0.4f * f, 0.5f)
        }
        val turn = Reframe.cropPath(clip, tall, hd, back, Point(0.5f, 0.5f))
        assertTrue(turn.keyframes.any { it.timeUs == 2 * sec })
        // Standing still: no keyframes.
        val still = (0..40).map { it * Reframe.SAMPLE_US to Point(0.6f, 0.5f) }
        assertTrue(Reframe.cropPath(clip, tall, hd, still, Point(0.5f, 0.5f)).keyframes.isEmpty())
    }

    @Test
    fun titlesAreMovedJustInsideTheFrame() {
        // A title on the right of a 16:9 frame is half out of a 9:16 one.
        val moved = Reframe.inside(Transform(x = 0.35f), Reframe.Box(0.3f, 0.45f, 0.7f, 0.55f))
        assertEquals(0.95f, 0.5f + (0.7f - 0.5f) + moved.x, 0.0001f)
        assertEquals(0f, moved.y, 0.0001f)
        // Already inside: it stays.
        assertEquals(
            Transform(y = 0.2f),
            Reframe.inside(Transform(y = 0.2f), Reframe.Box(0.3f, 0.45f, 0.7f, 0.55f)),
        )
        // Too wide to fit: centred.
        val wide = Reframe.inside(Transform(x = 0.2f), Reframe.Box(0f, 0.4f, 1f, 0.6f))
        assertEquals(0f, wide.x, 0.0001f)
    }

    @Test
    fun aCopyInTheNewFormatFollowsCentresAndKeepsTitlesIn() {
        val follow = Clip("a", ClipType.Video, 0, 4 * sec, MediaRef("tram", 0, 4 * sec))
        val other = Clip(
            "b",
            ClipType.Video,
            4 * sec,
            2 * sec,
            MediaRef("tram", 4 * sec, 6 * sec),
            transform = Keyframed(Transform(x = 0.2f)),
        )
        val pip = Clip(
            "pip",
            ClipType.Video,
            0,
            2 * sec,
            MediaRef("tram", 0, 2 * sec),
            transform = Keyframed(Transform(scale = 0.4f, x = 0.25f)),
        )
        val title = Clip(
            "title",
            ClipType.Text,
            0,
            2 * sec,
            text = TextSpec("DAY ONE"),
            transform = Keyframed(Transform(x = 0.35f)),
        )
        val project = Project(
            "p",
            "Lisbon weekend",
            format = Format(ratio = AspectRatio.Landscape16x9),
            media = listOf(video),
            layers = listOf(
                Layer("L1", clips = listOf(follow, other)),
                Layer("L2", clips = listOf(pip)),
                Layer("L3", clips = listOf(title)),
            ),
        )
        val moving = (0..40).map { it * Reframe.SAMPLE_US to Point(0.3f + it * 0.01f, 0.5f) }
        val copy = Reframe.apply(
            project,
            AspectRatio.Portrait9x16,
            Reframe.Follow.Subject,
            samples = mapOf("a" to moving),
            motion = Reframe.DEFAULT_MOTION,
            boxes = mapOf("title" to Reframe.Box(0.3f, 0.45f, 0.7f, 0.55f)),
        )
        assertEquals(AspectRatio.Portrait9x16, copy.format.ratio)
        assertEquals(project.format.resolution, copy.format.resolution)
        val (_, a) = copy.findClip("a")!!
        assertEquals(2, a.transform.keyframes.size)
        // b had nothing to follow: the middle of its picture, in the middle.
        val (_, b) = copy.findClip("b")!!
        assertEquals(Transform(), b.transform.value)
        // The picture in picture keeps its place; the title comes inside.
        assertEquals(pip.transform, copy.findClip("pip")!!.second.transform)
        val movedTitle = copy.findClip("title")!!.second.transform.value
        assertTrue(abs(movedTitle.x - 0.25f) < 0.0001f)
        // Without boxes, titles stay where they were.
        val loose = Reframe.apply(
            project,
            AspectRatio.Portrait9x16,
            Reframe.Follow.Fixed,
            emptyMap(),
            Reframe.DEFAULT_MOTION,
            null,
        )
        assertEquals(title.transform, loose.findClip("title")!!.second.transform)
    }
}
