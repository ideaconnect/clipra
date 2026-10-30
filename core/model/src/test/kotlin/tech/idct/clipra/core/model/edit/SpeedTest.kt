package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.SpeedCurve
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.mediaTimeAt
import tech.idct.clipra.core.model.timelineAt
import kotlin.math.abs

/** Speed (#22): constant speeds, ramps, reverse, and edits on clips that play at a speed. */
class SpeedTest {
    private val sec = 1_000_000L

    // L1: "a" (0-4 s, media 1-5 s) then "b" (4-6 s). L2: music linked to "b" at 5-7 s.
    // L3: a title at 0-3 s.
    private val project = Project(
        id = "p",
        name = "Speed",
        media = listOf(
            MediaAsset("v", "content://v", MediaKind.Video, durationUs = 10 * sec),
            MediaAsset("m", "content://m", MediaKind.Audio, durationUs = 10 * sec),
        ),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    video("a", 0, MediaRef("v", 1 * sec, 5 * sec)),
                    video("b", 4 * sec, MediaRef("v", 0, 2 * sec)),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    Clip(
                        "song",
                        ClipType.Audio,
                        startUs = 5 * sec,
                        durationUs = 2 * sec,
                        source = MediaRef("m", 0, 2 * sec),
                    ),
                ),
            ),
            Layer(
                "L3",
                clips = listOf(
                    Clip("title", ClipType.Text, 0, 3 * sec, text = TextSpec("Lisbon")),
                ),
            ),
        ),
        links = listOf(LinkGroup("g", listOf("b", "song"))),
    )

    private fun video(id: String, start: Long, media: MediaRef) = Clip(
        id = id,
        type = ClipType.Video,
        startUs = start,
        durationUs = media.outPointUs - media.inPointUs,
        source = media,
    )

    private fun Project.clip(id: String) = findClip(id)!!.second

    private fun applyAndCheckInverse(command: EditCommand, from: Project = project): Project {
        val history = EditHistory(from)
        val edited = history.apply(command)
        assertEquals(from, history.undo())
        assertEquals(edited, history.redo())
        return edited
    }

    @Test
    fun aConstantSpeedChangesTheLengthAndRipplesLaterClips() {
        val fast = applyAndCheckInverse(SetSpeed("a", Speed(rate = 2.0)))
        assertEquals(2 * sec, fast.clip("a").durationUs)
        // The clip after it and its linked music move up; the title on another layer stays.
        assertEquals(2 * sec, fast.clip("b").startUs)
        assertEquals(3 * sec, fast.clip("song").startUs)
        assertEquals(0L, fast.clip("title").startUs)

        val slow = applyAndCheckInverse(SetSpeed("a", Speed(rate = 0.5)))
        assertEquals(8 * sec, slow.clip("a").durationUs)
        assertEquals(8 * sec, slow.clip("b").startUs)
        assertEquals(9 * sec, slow.clip("song").startUs)

        // Back to 1x gives the clip its length back.
        val back = SetSpeed("a", Speed()).apply(fast)
        assertEquals(project.clip("a"), back.clip("a"))
        assertEquals(4 * sec, back.clip("b").startUs)
    }

    @Test
    fun theMediaPlaysAtTheSpeedForwardsOrReversed() {
        val fast = SetSpeed("a", Speed(rate = 2.0)).apply(project).clip("a")
        assertEquals(3 * sec, fast.mediaTimeAt(1 * sec))
        assertEquals(5 * sec, fast.mediaTimeAt(2 * sec))

        val reversed = SetSpeed("a", Speed(reverse = true)).apply(project).clip("a")
        assertEquals(4 * sec, reversed.durationUs)
        assertEquals(5 * sec, reversed.mediaTimeAt(0))
        assertEquals(4 * sec, reversed.mediaTimeAt(1 * sec))
        assertEquals(1 * sec, reversed.mediaTimeAt(4 * sec))
        // And back: the moment a frame plays.
        assertEquals(1 * sec, reversed.timelineAt(4 * sec))
    }

    @Test
    fun rampsChangeTheSpeedAlongTheClip() {
        val span = 4 * sec
        val hero = Speed(ramp = "hero")
        // Slow in the middle: longer than the media; Flash in starts fast: shorter.
        assertTrue(SpeedCurve.timelineDuration(hero, span) > span)
        assertTrue(SpeedCurve.timelineDuration(Speed(ramp = "flash_in"), span) < span)
        assertEquals(1.0, SpeedCurve.mediaSpeedAt(hero, span, 0), 0.05)
        assertEquals(0.25, SpeedCurve.mediaSpeedAt(hero, span, span / 2), 0.05)
        assertEquals(0.1, SpeedCurve.mediaSpeedAt(Speed(ramp = "bullet"), span, span / 2), 0.01)

        // A Custom ramp plays its own speeds.
        val custom = Speed(ramp = SpeedCurve.CUSTOM, custom = listOf(2f, 2f, 2f))
        assertEquals(span / 2, SpeedCurve.timelineDuration(custom, span))

        val ramped = applyAndCheckInverse(SetSpeed("a", hero))
        // In steps of a tenth of a second of media.
        assertEquals(40, ramped.clip("a").speed.rampSteps)
        val duration = SpeedCurve.timelineDuration(ramped.clip("a").speed, span)
        assertEquals(duration, ramped.clip("a").durationUs)
        assertEquals(duration, ramped.clip("b").startUs)
        // The clip plays all of its media, start to end.
        assertEquals(1 * sec, ramped.clip("a").mediaTimeAt(0))
        assertEquals(5 * sec, ramped.clip("a").mediaTimeAt(duration))
    }

    @Test
    fun soundAndPictureAgreeOnARamp() {
        // The steps Media3 plays the sound in add up to the clip's length on the timeline.
        val span = 4 * sec
        for (ramp in SpeedCurve.ramps.keys) {
            val speed = Speed(ramp = ramp)
            var at = 0L
            var played = 0.0
            while (true) {
                val next = SpeedCurve.nextSpeedChangeUs(speed, span, at) ?: span
                assertTrue(next > at)
                played += (next - at) / SpeedCurve.mediaSpeedAt(speed, span, at)
                if (next >= span) break
                at = next
            }
            assertEquals(ramp, SpeedCurve.timelineDuration(speed, span).toDouble(), played, 1000.0)
        }
        assertNull(SpeedCurve.nextSpeedChangeUs(Speed(rate = 2.0), span, 0))
    }

    @Test
    fun timelineAtUndoesMediaTimeAt() {
        listOf(Speed(ramp = "montage"), Speed(ramp = "bullet", reverse = true)).forEach { speed ->
            val clip = SetSpeed("a", speed).apply(project).clip("a")
            for (t in 0..clip.durationUs step 250_000) {
                val media = clip.mediaTimeAt(t)!!
                assertNear("$speed at $t", t, clip.timelineAt(media)!!)
            }
        }
    }

    @Test
    fun splittingOrTrimmingARampKeepsEveryMoment() {
        listOf(
            Speed(ramp = "montage"),
            Speed(ramp = "flash_out", reverse = true),
            Speed(rate = 1.5, reverse = true),
        ).forEach { speed ->
            val whole = SetSpeed("a", speed).apply(project)
            val clip = whole.clip("a")
            val cut = clip.durationUs * 2 / 5
            val split = SplitClip("a", cut) { "a2" }.apply(whole)
            val left = split.clip("a")
            val right = split.clip("a2")
            assertEquals(clip.durationUs, left.durationUs + right.durationUs)
            for (t in 0 until clip.durationUs step 200_000) {
                val part = if (t < cut) left else right
                assertNear(
                    "$speed split at $t",
                    clip.mediaTimeAt(t)!!,
                    part.mediaTimeAt(t - part.startUs)!!,
                )
            }
            // Trimming the start keeps what is left playing as before.
            val trimmed = TrimClip("a", Edge.Start, cut).apply(whole).clip("a")
            for (t in cut until clip.durationUs step 200_000) {
                assertNear(
                    "$speed trim at $t",
                    clip.mediaTimeAt(t)!!,
                    trimmed.mediaTimeAt(t - cut)!!,
                )
            }
        }
    }

    @Test
    fun aFreezeFrameHoldsTheFrameOnScreenAtAnySpeed() {
        val fast = SetSpeed("a", Speed(rate = 2.0)).apply(project)
        val edited = FreezeFrame("a", 1 * sec, 1 * sec, ids("a2", "still")).apply(fast)
        assertEquals(3 * sec, edited.clip("still").source!!.inPointUs)
        assertEquals(3 * sec, edited.clip("a2").mediaTimeAt(0))

        val reversed = SetSpeed("a", Speed(reverse = true)).apply(project)
        val held = FreezeFrame("a", 1 * sec, 1 * sec, ids("a2", "still")).apply(reversed)
        assertEquals(4 * sec, held.clip("still").source!!.inPointUs)
        // The rest goes on backwards from there.
        assertEquals(4 * sec, held.clip("a2").mediaTimeAt(0))
        assertEquals(1 * sec, held.clip("a2").mediaTimeAt(held.clip("a2").durationUs))
    }

    @Test
    fun onlyMovingPicturesAndSoundHaveASpeed() {
        assertThrows(EditRejected::class.java) {
            SetSpeed("title", Speed(rate = 2.0)).apply(project)
        }
        val locked = project.updateLayer("L1") { it.copy(locked = true) }
        assertThrows(EditRejected::class.java) {
            SetSpeed("a", Speed(rate = 2.0)).apply(locked)
        }
        // Sound plays at a speed too.
        assertEquals(
            1 * sec,
            SetSpeed("song", Speed(rate = 2.0)).apply(project).clip("song").durationUs,
        )
    }

    private fun ids(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    /** Within a millisecond: the ramp's steps round, a frame is 33 ms. */
    private fun assertNear(message: String, expected: Long, actual: Long) {
        assertTrue("$message: $expected vs $actual", abs(expected - actual) <= 1_000)
    }
}
