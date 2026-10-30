package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.SetAnimation
import tech.idct.clipra.core.model.edit.SplitClip

/** Animations (#33): what clips and text do at each moment, and edits around them. */
class AnimationsTest {
    private val sec = 1_000_000L
    private val base = Transform(x = 0.1f, scale = 1.2f)

    private fun motion(animation: Animation, offset: Long, duration: Long = 3 * sec) =
        Animations.motion(animation, base, offset, duration)

    @Test
    fun clipsComeInAndGoOut() {
        val zoom = Animation(enter = "zoom_in", exit = "slide_down")
        // In: small and clear at the start, the clip's own place once 0.6 s have passed.
        assertEquals(0f, motion(zoom, 0).opacity, 0f)
        assertEquals(1.2f * 0.6f, motion(zoom, 0).transform.scale, 1e-5f)
        assertEquals(Animations.Motion(base, 1f), motion(zoom, 600_000))
        assertEquals(Animations.Motion(base, 1f), motion(zoom, 1_500_000))
        // Out: sliding down over the last 0.6 s.
        assertTrue(motion(zoom, 2_900_000).transform.y > 0.3f)
        assertEquals(0.1f, motion(zoom, 2_900_000).transform.x, 0f)

        assertEquals(0.5f, motion(Animation(enter = "fade"), 300_000).opacity, 1e-5f)
        assertEquals(0.5f, motion(Animation(exit = "fade"), 2_700_000).opacity, 1e-5f)
        val spin = motion(Animation(enter = "spin"), 0).transform
        assertEquals(-180f, spin.rotation, 0f)
        assertTrue(motion(Animation(enter = "slide_up"), 0).transform.y > 0.5f)
        assertTrue(motion(Animation(exit = "zoom_out"), 3 * sec).transform.scale < 1f)
    }

    @Test
    fun aShortClipAnimatesInHalfItsLength() {
        assertEquals(500_000L, Animations.lengthUs(1_000_000))
        assertEquals(600_000L, Animations.lengthUs(10 * sec))
        val fade = Animation(enter = "fade", exit = "fade")
        assertEquals(0.5f, motion(fade, 250_000, 1_000_000).opacity, 1e-5f)
        assertEquals(0.5f, motion(fade, 750_000, 1_000_000).opacity, 1e-5f)
    }

    @Test
    fun loopsKeepGoing() {
        val burns = Animation(loop = "ken_burns")
        assertEquals(base, motion(burns, 0).transform)
        assertEquals(1.2f * 1.12f, motion(burns, 3 * sec).transform.scale, 1e-5f)
        val pulse = Animation(loop = "pulse")
        val scales = (0..30).map { motion(pulse, it * 100_000L).transform.scale }
        assertTrue(scales.max() > 1.2f * 1.03f && scales.min() < 1.2f * 0.97f)
        val shake = Animation(loop = "shake")
        val xs = (0..30).map { motion(shake, it * 33_333L).transform.x }
        assertTrue(xs.max() - xs.min() > 0.015f)
        // Fully opaque throughout.
        assertEquals(1f, motion(shake, 1_234_567).opacity, 0f)
    }

    @Test
    fun textMovesOnlyWhileItAnimates() {
        val typed = Animation(enter = "typewriter", exit = "fade")
        assertEquals(
            Animations.TextMotion("typewriter", 0.5f, exit = false),
            Animations.textMotion(
                typed,
                300_000,
                3 * sec,
            ),
        )
        assertNull(Animations.textMotion(typed, 1_500_000, 3 * sec))
        assertEquals(
            Animations.TextMotion("fade", 0.5f, exit = true),
            Animations.textMotion(
                typed,
                2_700_000,
                3 * sec,
            ),
        )
        assertNull(Animations.textMotion(null, 0, 3 * sec))
        assertEquals(1f, Animations.backOut(1f), 1e-5f)
        assertEquals(1f, Animations.bounceOut(1f), 1e-5f)
        assertTrue((0..20).any { Animations.backOut(it / 20f) > 1f })
    }

    @Test
    fun clipsAreAnimatedAndSplitKeepsEachEnd() {
        val clip = Clip("v", ClipType.Video, 0, 4 * sec, MediaRef("v", 0, 4 * sec))
        val project = Project(
            id = "p",
            name = "Animations",
            layers = listOf(
                Layer("L1", clips = listOf(clip)),
                Layer(
                    "L2",
                    clips = listOf(Clip("m", ClipType.Audio, 0, sec, MediaRef("m", 0, sec))),
                ),
            ),
        )
        val all = Animation(enter = "zoom_in", exit = "fade", loop = "pulse")
        val animated = SetAnimation("v", all).apply(project)
        assertEquals(all, animated.findClip("v")!!.second.animation)
        assertNull(SetAnimation("v", Animation()).apply(animated).findClip("v")!!.second.animation)
        assertThrows(EditRejected::class.java) { SetAnimation("m", all).apply(project) }

        // The way in stays at the start, the way out at the end; both keep the loop.
        val split = SplitClip("v", 2 * sec) { "v2" }.apply(animated)
        assertEquals(
            Animation(enter = "zoom_in", loop = "pulse"),
            split.findClip("v")!!.second.animation,
        )
        assertEquals(
            Animation(exit = "fade", loop = "pulse"),
            split.findClip("v2")!!.second.animation,
        )
    }
}
