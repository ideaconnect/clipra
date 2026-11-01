package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transitions
import tech.idct.clipra.core.model.cutAfter
import tech.idct.clipra.core.model.cuts
import tech.idct.clipra.core.model.transitionOn

class TransitionCommandsTest {
    private val sec = 1_000_000L

    private fun clip(id: String, start: Long, length: Long, type: ClipType = ClipType.Video) =
        Clip(id, type, start, length, MediaRef(id, 0, length))

    // L1: a | b | (gap) c. L2: music | music2 (no picture). L3 (locked): d | e.
    private val project = Project(
        id = "p",
        name = "p",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    clip("a", 0, 4 * sec),
                    clip("b", 4 * sec, 1 * sec, ClipType.Photo),
                    clip("c", 6 * sec, 2 * sec),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    clip("music", 0, 3 * sec, ClipType.Audio),
                    clip("music2", 3 * sec, 3 * sec, ClipType.Audio),
                ),
            ),
            Layer(
                "L3",
                clips = listOf(clip("d", 0, 2 * sec), clip("e", 2 * sec, 2 * sec)),
                locked = true,
            ),
        ),
    )

    private fun ids(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    @Test
    fun cutsAreAdjacentPictureClips() {
        val cuts = project.cuts()
        assertEquals(listOf("a" to "b", "d" to "e"), cuts.map { it.from.id to it.to.id })
        assertEquals(4 * sec, project.cutAfter("a")!!.timeUs)
        assertNull(project.cutAfter("b"))
        assertNull(project.cutAfter("music"))
    }

    @Test
    fun setReplaceAndRemove() {
        val dissolve = SetTransition("L1", 4 * sec, "dissolve", newId = ids("t1")).apply(project)
        val cut = dissolve.cutAfter("a")!!
        assertEquals("dissolve", dissolve.transitionOn(cut)?.type)

        val spin = SetTransition("L1", 4 * sec, "spin", 800_000, Easing.Linear).apply(dissolve)
        assertEquals(1, spin.transitions.size)
        assertEquals("t1", spin.transitions.single().id)
        assertEquals("spin", spin.transitions.single().type)
        assertEquals(Easing.Linear, spin.transitions.single().easing)

        val none = SetTransition("L1", 4 * sec, null).apply(spin)
        assertEquals(emptyList<Any>(), none.transitions)
    }

    @Test
    fun durationStaysWithinLimitsAndTheClips() {
        // b is 1 s long, so at most 2 s; the range caps at 2 s anyway.
        val long = SetTransition("L1", 4 * sec, "dissolve", 5 * sec).apply(project)
        assertEquals(Transitions.MAX_DURATION_US, long.transitions.single().durationUs)
        val short = SetTransition("L1", 4 * sec, "dissolve", 10_000).apply(project)
        assertEquals(Transitions.MIN_DURATION_US, short.transitions.single().durationUs)
        val tight = project.copy(
            layers = listOf(
                Layer("L1", clips = listOf(clip("x", 0, 300_000), clip("y", 300_000, sec))),
            ),
        )
        val fitted = SetTransition("L1", 300_000, "dissolve", 2 * sec).apply(tight)
        assertEquals(600_000, fitted.transitions.single().durationUs)
    }

    @Test
    fun applyToAllSkipsLockedLayers() {
        val edited = ApplyTransitionToAll("flash").apply(project)
        assertEquals(listOf("L1"), edited.transitions.map { it.layerId })
        val cleared = ApplyTransitionToAll(null).apply(edited)
        assertEquals(emptyList<Any>(), cleared.transitions)
    }

    @Test
    fun refusals() {
        assertThrows(EditRejected::class.java) {
            SetTransition("L1", 5 * sec, "dissolve").apply(project)
        }
        assertThrows(EditRejected::class.java) {
            SetTransition("L3", 2 * sec, "dissolve").apply(project)
        }
        assertThrows(IllegalArgumentException::class.java) {
            SetTransition("L1", 4 * sec, "sparkle").apply(project)
        }
    }

    @Test
    fun easingShapesProgress() {
        assertEquals(0.5f, Transitions.ease(Easing.Linear, 0.5f))
        assertEquals(0.5f, Transitions.ease(Easing.EaseInOut, 0.5f), 1e-6f)
        assertEquals(0.104f, Transitions.ease(Easing.EaseInOut, 0.2f), 1e-6f)
        // A tenth of the way in already shows (a cubic curve showed 0.4%).
        assertTrue(Transitions.ease(Easing.EaseInOut, 0.1f) > 0.02f)
        assertEquals(0.75f, Transitions.ease(Easing.EaseOut, 0.5f))
        assertEquals(1f, Transitions.ease(Easing.EaseOut, 2f))
        assertEquals(24, Transitions.all.toSet().size)
    }
}
