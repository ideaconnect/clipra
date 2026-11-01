package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Keyframes
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.at
import tech.idct.clipra.core.model.keyframeTimes
import tech.idct.clipra.core.model.maskAt
import tech.idct.clipra.core.model.opacityAt
import tech.idct.clipra.core.model.transformAt

/** Keyframes (#21): interpolation, the keyframe button, moving, easing, and edits around them. */
class KeyframesTest {
    private val clip = Clip(
        id = "c",
        type = ClipType.Video,
        startUs = 2_000_000,
        durationUs = 4_000_000,
        source = MediaRef("v", 0, 4_000_000),
    )
    private val project = Project(
        id = "p",
        name = "Keyframes",
        media = listOf(MediaAsset("v", "content://v", MediaKind.Video, durationUs = 10_000_000)),
        layers = listOf(Layer("L1", clips = listOf(clip))),
    )

    private fun Project.c() = layers.flatMap { it.clips }.first {
        it.id == "c" ||
            it.startUs == 2_000_000L
    }

    @Test
    fun valuesMoveBetweenKeyframesAndHoldOutside() {
        val keyed = Keyframed(
            0f,
            listOf(Keyframe(1_000_000, 0f, Easing.Linear), Keyframe(3_000_000, 1f)),
        )
        assertEquals(0f, keyed.at(0, Keyframes::opacity), 0f)
        assertEquals(0.5f, keyed.at(2_000_000, Keyframes::opacity), 1e-6f)
        assertEquals(0.25f, keyed.at(1_500_000, Keyframes::opacity), 1e-6f)
        assertEquals(1f, keyed.at(5_000_000, Keyframes::opacity), 0f)

        // Ease in-out: slow at the ends, the same in the middle.
        val eased = keyed.copy(
            keyframes = keyed.keyframes.map {
                it.copy(easing = Easing.EaseInOut)
            },
        )
        assertEquals(0.5f, eased.at(2_000_000, Keyframes::opacity), 1e-6f)
        assertEquals(0.15625f, eased.at(1_500_000, Keyframes::opacity), 1e-6f)

        // Without keyframes, the plain value.
        assertEquals(0.7f, Keyframed(0.7f).at(2_000_000, Keyframes::opacity), 0f)
    }

    @Test
    fun theButtonKeysTheClipAtThePlayheadAndDeletesTheKeyframeThere() {
        // At 3 s on the timeline (1 s into the clip): the clip's current place and opacity.
        var edited = ToggleKeyframe("c", 3_000_000, KeyframeTarget.Transform).apply(project)
        assertEquals(listOf(1_000_000L), edited.c().keyframeTimes)

        // Moving the clip at 5 s keys it there and animates between the two.
        edited = SetTransform("c", Transform(x = 0.4f, scale = 2f), atUs = 5_000_000).apply(edited)
        edited = SetBlend("c", edited.c().blend, 0.2f, atUs = 5_000_000).apply(edited)
        val animated = edited.c()
        assertEquals(listOf(1_000_000L, 3_000_000L), animated.keyframeTimes)
        assertEquals(0f, animated.transformAt(1_000_000).x, 0f)
        assertEquals(0.4f, animated.transformAt(3_000_000).x, 0f)
        assertEquals(0.2f, animated.transformAt(2_000_000).x, 1e-6f)
        assertEquals(1.5f, animated.transformAt(2_000_000).scale, 1e-6f)
        assertEquals(0.6f, animated.opacityAt(2_000_000), 1e-6f)

        // Deleting the first leaves the second; deleting that one keeps its values.
        edited = ToggleKeyframe("c", 3_000_000, KeyframeTarget.Transform).apply(edited)
        edited = ToggleKeyframe("c", 5_000_000, KeyframeTarget.Transform).apply(edited)
        assertTrue(edited.c().keyframeTimes.isEmpty())
        assertEquals(0.4f, edited.c().transform.value.x, 0f)
        assertEquals(0.2f, edited.c().opacity.value, 0f)
    }

    @Test
    fun withoutKeyframesAnEditSetsThePlainValue() {
        val edited = SetTransform("c", Transform(x = 0.3f), atUs = 3_000_000).apply(project)
        assertEquals(0.3f, edited.c().transform.value.x, 0f)
        assertTrue(edited.c().keyframeTimes.isEmpty())
    }

    @Test
    fun keyframesMoveAndChangeTheirEasing() {
        var edited = animated()
        edited = MoveKeyframe("c", 3_000_000, 2_000_000).apply(edited)
        assertEquals(listOf(0L, 2_000_000L), edited.c().keyframeTimes)
        // Kept within the clip.
        edited = MoveKeyframe("c", 2_000_000, 9_000_000).apply(edited)
        assertEquals(listOf(0L, 4_000_000L), edited.c().keyframeTimes)

        edited = SetKeyframeEasing("c", 0, Easing.Linear).apply(edited)
        assertEquals(Easing.Linear, edited.c().transform.keyframes.first().easing)
        assertEquals(0.25f, edited.c().transformAt(2_000_000).x, 1e-6f)
    }

    @Test
    fun aMaskHasItsOwnKeyframes() {
        val oval = Mask(shape = MaskShape.Oval, x = 0.2f)
        var edited = SetMask("c", oval).apply(project)
        edited = ToggleKeyframe("c", 2_000_000, KeyframeTarget.Mask).apply(edited)
        edited = SetMask("c", oval.copy(x = 0.8f), atUs = 6_000_000).apply(edited)
        val masked = edited.c()
        assertEquals(listOf(0L, 4_000_000L), masked.maskKeyframes.map { it.timeUs })
        assertEquals(0.5f, masked.maskAt(2_000_000)!!.x, 1e-6f)
        // The transform is not animated by the mask's keyframes.
        assertTrue(masked.transform.keyframes.isEmpty())
        // Without a mask there is nothing to key.
        assertThrows(EditRejected::class.java) {
            ToggleKeyframe("c", 3_000_000, KeyframeTarget.Mask).apply(project)
        }
        // Taking the mask away takes its keyframes.
        assertTrue(SetMask("c", null).apply(edited).c().maskKeyframes.isEmpty())
        assertNull(SetMask("c", null).apply(edited).c().mask)
    }

    @Test
    fun splitAndTrimKeepTheAnimationOnTheTimeline() {
        val edited = animated()
        val before = (2_000_000L..6_000_000L step 250_000).associateWith { timeline ->
            edited.c().transformAt(timeline - 2_000_000).x
        }
        // Split at 4 s: each part moves exactly as the whole did.
        val split = SplitClip("c", 4_000_000) { "r" }.apply(edited)
        val parts = split.layers.first().clips
        before.forEach { (timeline, x) ->
            val part = parts.first { timeline in it.startUs..it.endUs }
            assertEquals("at $timeline", x, part.transformAt(timeline - part.startUs).x, 1e-5f)
        }
        // Trim the start to 3 s: the same.
        val trimmed = TrimClip(
            "c",
            Edge.Start,
            3_000_000,
        ).apply(edited).layers.first().clips.single()
        before.filterKeys { it >= 3_000_000 }.forEach { (timeline, x) ->
            assertEquals(
                "at $timeline",
                x,
                trimmed.transformAt(timeline - trimmed.startUs).x,
                1e-5f,
            )
        }
    }

    @Test
    fun outsideTheClipThereIsNoKeyframeButton() {
        assertThrows(EditRejected::class.java) {
            ToggleKeyframe("c", 7_000_000, KeyframeTarget.Transform).apply(project)
        }
    }

    /** x from 0 at the clip's start to 0.5 at 3 s into it (5 s on the timeline). */
    private fun animated(): Project {
        var edited = ToggleKeyframe("c", 2_000_000, KeyframeTarget.Transform).apply(project)
        edited = SetTransform("c", Transform(x = 0.5f), atUs = 5_000_000).apply(edited)
        return edited
    }
}
