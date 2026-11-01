package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.edit.EditHistory
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.FollowFace
import kotlin.math.abs
import kotlin.math.sin

/** Follow face (#52): the face's path, the mask over the head, and the keyframes it writes. */
class FaceTrackingTest {
    private fun sample(i: Int, x: Float?) =
        FaceSample(i * FaceTracking.SAMPLE_US, x?.let { FaceBox(it, 0.3f, 0.2f, 0.12f) })

    @Test
    fun gapsAreFilledAndTheEndsHeld() {
        val samples = listOf(null, 0.2f, null, null, 0.5f, null).mapIndexed(::sample)
        val path = FaceTracking.track(samples)!!
        assertEquals(6, path.size)
        // Monotonic between the faces, and no jump at the ends.
        val xs = path.map { it.second.x }
        assertTrue(xs.zipWithNext().all { (a, b) -> b >= a - 1e-6f })
        assertTrue(xs.first() < 0.3f && xs.last() > 0.4f)
        assertNull(FaceTracking.track(listOf(null, null).mapIndexed(::sample)))
    }

    @Test
    fun theDetectorsJitterIsSmoothedButMotionFollowed() {
        // A head moving right steadily, the detector a hundredth off either way frame by frame.
        val samples = (0 until 40).map { i ->
            sample(
                i,
                0.2f + i * 0.01f + (if (i % 2 == 0) 0.01f else -0.01f),
            )
        }
        val path = FaceTracking.track(samples)!!
        for (i in 5 until 35) {
            val truth = 0.2f + i * 0.01f
            assertEquals("at $i", truth, path[i].second.x, 0.004f)
        }
        // Steps between samples even out.
        val steps = path.zipWithNext().drop(4).dropLast(4).map { (a, b) -> b.second.x - a.second.x }
        assertTrue(steps.all { abs(it - 0.01f) < 0.004f })
        // A faster sway still comes through at most of its size.
        val sway = (0 until 40).map { i -> sample(i, 0.5f + 0.1f * sin(i / 3f)) }
        val swayed = FaceTracking.track(sway)!!.map { it.second.x }
        assertTrue(swayed.max() - swayed.min() > 0.16f)
    }

    @Test
    fun theMaskCoversTheHeadAndKeepsItsLook() {
        val face = FaceBox(0.5f, 0.3f, 0.2f, 0.12f)
        val look =
            Mask(shape = MaskShape.Rectangle, feather = 0.06f, invert = true, effect = "blur")
        val mask = FaceTracking.maskOver(face, look)
        assertEquals(MaskShape.Rectangle, mask.shape)
        assertTrue(mask.followFace && mask.invert)
        assertEquals("blur", mask.effect)
        assertEquals(0.06f, mask.feather, 0f)
        assertEquals(0.3f, mask.width, 0.001f)
        assertEquals(0.228f, mask.height, 0.001f)
        // A little higher than the face's box, for the forehead and hair.
        assertEquals(0.282f, mask.y, 0.001f)
        // No shape yet: an oval.
        assertEquals(MaskShape.Oval, FaceTracking.maskOver(face, Mask()).shape)
    }

    @Test
    fun keyframesGoInFiveTimesASecondInAStraightLine() {
        val path = FaceTracking.track((0 until 30).map { sample(it, 0.4f) })!!
        val keyframes = FaceTracking.keyframes(path, Mask())
        assertEquals(15, keyframes.size)
        assertEquals(listOf(0L, 200_000L, 400_000L), keyframes.take(3).map { it.timeUs })
        assertTrue(keyframes.all { it.easing == Easing.Linear && it.value.followFace })
    }

    private val clip = Clip("v", ClipType.Video, 1_000_000, 3_000_000, MediaRef("m", 0, 3_000_000))
    private val project =
        Project(id = "p", name = "p", layers = listOf(Layer("L1", clips = listOf(clip))))
    private val path = FaceTracking.track((0 until 30).map { sample(it, 0.3f + it * 0.01f) })!!

    @Test
    fun followFaceWritesEditableKeyframesInOneStep() {
        val history = EditHistory(project)
        val following = history.apply(
            FollowFace("v", path, atUs = 1_000_000, addIfMissing = "mosaic"),
        )
        val followed = following.findClip("v")!!.second
        assertEquals(15, followed.maskKeyframes.size)
        assertTrue(followed.mask!!.followFace)
        assertTrue(followed.effects.any { it.type == "mosaic" })
        // Halfway through, the mask is where the face was then.
        assertEquals(0.45f, followed.maskAt(1_500_000)!!.x, 0.01f)
        assertEquals(project, history.undo())

        // Stopping leaves the mask where it is, without keyframes.
        val stopped = FollowFace(
            "v",
            null,
            atUs = 2_500_000,
        ).apply(following).findClip("v")!!.second
        assertTrue(stopped.maskKeyframes.isEmpty())
        assertEquals(0.45f, stopped.mask!!.x, 0.01f)
        assertTrue(!stopped.mask!!.followFace)
        assertThrows(EditRejected::class.java) {
            FollowFace("v", emptyList(), atUs = 0).apply(project)
        }
    }

    @Test
    fun theFaceUnderTheMaskIsFollowed() {
        // Two people: one sits on the left, one walks in from the right. The detector is surer
        // of the one sitting.
        fun box(x: Float) = FaceBox(x, 0.4f, 0.1f, 0.12f)
        val seen = listOf(0.8f, 0.7f, 0.6f, 0.5f).map { walker -> listOf(box(0.2f), box(walker)) }

        // Without a mask, the surest face from the start.
        assertEquals(List(4) { 0.2f }, FaceTracking.follow(seen).map { it!!.x })
        // The mask put on the walker at the third sample follows the walker both ways.
        assertEquals(
            listOf(0.8f, 0.7f, 0.6f, 0.5f),
            FaceTracking.follow(seen, anchor = 2, near = box(0.62f)).map { it!!.x },
        )
        // A sample with no face stays empty and the path goes on from the last face.
        val gap = seen.toMutableList().also { it[1] = emptyList() }
        assertEquals(
            listOf(0.8f, null, 0.6f, 0.5f),
            FaceTracking.follow(gap, anchor = 3, near = box(0.5f)).map { it?.x },
        )
    }

    @Test
    fun aLostFaceDoesNotJumpToSomeoneElse() {
        // A small face (0.04 wide) walking right, lost for two samples while a stranger stands
        // far off, then seen again.
        fun face(x: Float) = FaceBox(x, 0.4f, 0.04f, 0.07f)
        val stranger = face(0.1f)
        // Something taken for a face nearby, three times as big, isn't it either.
        val sign = FaceBox(0.55f, 0.45f, 0.12f, 0.2f)
        val seen = listOf(
            listOf(face(0.5f), stranger),
            listOf(stranger, sign),
            listOf(stranger),
            listOf(face(0.56f), stranger),
        )
        val picked = FaceTracking.follow(seen, anchor = 0, near = face(0.5f))
        assertEquals(listOf(0.5f, null, null, 0.56f), picked.map { it?.x })
    }

    @Test
    fun smallFacesAreLookedForInTilesOfThePicture() {
        // Follow face's mask over a face a twentieth of a 720p frame wide (#182): 12 tiles of
        // 410 px, where the face is 20 px of the detector's 128.
        val mask = FaceBox(0.5f, 0.5f, 0.075f, 0.17f)
        val tiles = FaceTracking.tiles(1280, 720, mask)
        assertEquals(12, tiles.size)
        val side = tiles.first().side
        assertEquals(410.0, side.toDouble(), 2.0)
        assertEquals(FaceTracking.Tile(0, 0, side), tiles.first())
        assertEquals(FaceTracking.Tile(1280 - side, 720 - side, side), tiles.last())
        // Neighbours overlap by at least a quarter, so a face on an edge is whole in one.
        val xs = tiles.map { it.x }.distinct()
        assertTrue(xs.zipWithNext().all { (a, b) -> b - a <= side * 3 / 4 })

        // A face big enough in the whole picture needs none; a tiny one at most 15.
        assertTrue(FaceTracking.tiles(1280, 720, FaceBox(0.5f, 0.5f, 0.3f, 0.5f)).isEmpty())
        val tiny = FaceTracking.tiles(1280, 720, FaceBox(0.5f, 0.5f, 0.01f, 0.02f))
        assertEquals(15, tiny.size)
        assertEquals(320, tiny.first().side)
        // In portrait the tiles stay inside the picture.
        val portrait = FaceTracking.tiles(720, 1280, FaceBox(0.5f, 0.5f, 0.06f, 0.04f))
        assertTrue(portrait.all { it.x + it.side <= 720 && it.y + it.side <= 1280 })
    }
}
