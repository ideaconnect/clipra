package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.Transition
import tech.idct.clipra.core.model.Transitions
import tech.idct.clipra.core.model.cutAfter
import tech.idct.clipra.core.model.cuts
import tech.idct.clipra.core.model.keepTransitionsOnTheirCuts
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

    // After every edit the undo history keeps transitions on their cuts (#218).

    private fun Project.transitionAfter(clipId: String): Transition? =
        cutAfter(clipId)?.let { transitionOn(it) }

    @Test
    fun movingAClipOffACutRemovesItsTransition() {
        val history = EditHistory(project)
        history.apply(SetTransition("L1", 4 * sec, "glitch", 2 * sec, newId = ids("t1")))
        history.apply(MoveClip("b", 4 * sec, LayerTarget.NewOnTop("L4")))
        assertEquals(emptyList<Any>(), history.project.transitions)
        // Undo brings back the whole project, the transition with it.
        history.undo()
        assertEquals("t1", history.project.transitionAfter("a")?.id)
    }

    @Test
    fun theReportedCaseLeavesNoTransitionOnOtherClips() {
        // A video, a photo and a video on L1: Glitch on the first cut, Film burn on the second.
        val start = project.copy(
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("v1", 0, 4 * sec),
                        clip("photo", 4 * sec, 3 * sec, ClipType.Photo),
                        clip("v2", 7 * sec, 4 * sec),
                    ),
                ),
            ),
        )
        val history = EditHistory(start)
        history.apply(SetTransition("L1", 4 * sec, "glitch", newId = ids("t1")))
        history.apply(SetTransition("L1", 7 * sec, "film-burn", 1_300_000, newId = ids("t2")))
        // The photo goes up a layer: both cuts are gone, and their transitions with them.
        history.apply(MoveClip("photo", 4 * sec, LayerTarget.NewOnTop("L2")))
        assertEquals(emptyList<Any>(), history.project.transitions)
        // The next video snaps to the first cut's time, and is split at the second's.
        history.apply(MoveClip("v2", 4 * sec))
        assertNull(history.project.transitionAfter("v1"))
        history.apply(SplitClip("v2", 7 * sec, newId = ids("v3")))
        assertNull(history.project.transitionAfter("v2"))
        assertEquals(emptyList<Any>(), history.project.transitions)
    }

    @Test
    fun aClipSplitAtAnOldCutGetsNoTransition() {
        val history = EditHistory(project)
        history.apply(SetTransition("L1", 4 * sec, "glitch", 2 * sec))
        history.apply(MoveClip("b", 4 * sec, LayerTarget.NewOnTop("L4")))
        history.apply(TrimClip("a", Edge.End, 6 * sec))
        history.apply(SplitClip("a", 4 * sec, newId = ids("a2")))
        assertEquals(4 * sec, history.project.cutAfter("a")!!.timeUs)
        assertNull(history.project.transitionAfter("a"))
    }

    @Test
    fun aShortClipSnappedToAnOldCutGetsNoTransition() {
        val history = EditHistory(project)
        history.apply(SetTransition("L1", 4 * sec, "glitch", 2 * sec))
        history.apply(MoveClip("b", 4 * sec, LayerTarget.NewOnTop("L4")))
        history.apply(AddClip(clip("x", 4 * sec, 300_000), LayerTarget.Existing("L1")))
        val cut = history.project.cutAfter("a")!!
        assertEquals("x", cut.to.id)
        assertEquals(600_000, cut.maxTransitionUs)
        assertNull(history.project.transitionOn(cut))
    }

    @Test
    fun aTransitionSavedOffItsCutIsDroppedAtTheNextEdit() {
        // Saved by an older version after its cut was gone: b ends at 5 s, c starts at 6 s.
        val saved = project.copy(
            transitions = listOf(Transition("old", "L1", 5 * sec, "film-burn", 1_300_000)),
        )
        val history = EditHistory(saved)
        history.apply(MoveClip("c", 5 * sec))
        assertNull(history.project.transitionAfter("b"))
        assertEquals(emptyList<Any>(), history.project.transitions)
    }

    @Test
    fun deletingAClipTakesItsTransitionsAlong() {
        // L1: a | b | c, with a transition on both cuts. The ripple brings c to a.
        val start = project.copy(
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("a", 0, 4 * sec),
                        clip("b", 4 * sec, 1 * sec),
                        clip("c", 5 * sec, 2 * sec),
                    ),
                ),
            ),
        )
        val history = EditHistory(start)
        history.apply(ApplyTransitionToAll("flash"))
        assertEquals(2, history.project.transitions.size)
        history.apply(DeleteClip("b"))
        assertEquals("c", history.project.cutAfter("a")!!.to.id)
        assertEquals(emptyList<Any>(), history.project.transitions)
    }

    @Test
    fun deletingTheSplitPartAtACutTakesItsTransitionAlong() {
        val history = EditHistory(project)
        history.apply(SplitClip("a", 2 * sec, newId = ids("a2")))
        history.apply(SetTransition("L1", 4 * sec, "dissolve"))
        // The ripple brings b to a, the other part of the same media.
        history.apply(DeleteClip("a2"))
        assertEquals("b", history.project.cutAfter("a")!!.to.id)
        assertEquals(emptyList<Any>(), history.project.transitions)
    }

    @Test
    fun aTransitionFollowsItsClipsWhenTheirCutMoves() {
        // L1: x | a | b, with a transition on a | b at 5 s.
        val start = project.copy(
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("x", 0, 1 * sec),
                        clip("a", 1 * sec, 4 * sec),
                        clip("b", 5 * sec, 1 * sec),
                    ),
                ),
            ),
        )
        val history = EditHistory(start)
        history.apply(SetTransition("L1", 5 * sec, "spin", newId = ids("t1")))
        // A ripple delete before the cut moves it 1 s earlier.
        history.apply(DeleteClip("x"))
        assertEquals("t1", history.project.transitionAfter("a")?.id)
        assertEquals(4 * sec, history.project.transitions.single().cutTimeUs)
        // A faster a ends earlier, and b moves up with it.
        history.apply(SetSpeed("a", Speed(rate = 2.0)))
        val cut = history.project.cutAfter("a")!!
        assertEquals(2 * sec, cut.timeUs)
        assertEquals("t1", history.project.transitionOn(cut)?.id)
        assertEquals(1, history.project.transitions.size)
    }

    @Test
    fun splittingEitherClipKeepsTheTransition() {
        val history = EditHistory(project)
        history.apply(SetTransition("L1", 4 * sec, "dissolve", newId = ids("t1")))
        // The outgoing clip's right part now ends at the cut.
        history.apply(SplitClip("a", 2 * sec, newId = ids("a2")))
        assertEquals("t1", history.project.transitionAfter("a2")?.id)
        assertNull(history.project.transitionAfter("a"))
        history.apply(SplitClip("b", 4_500_000, newId = ids("b2")))
        assertEquals("t1", history.project.transitionAfter("a2")?.id)
        assertEquals(1, history.project.transitions.size)
        // A freeze frame in the outgoing clip pushes the cut 1 s later.
        history.apply(FreezeFrame("a2", 3 * sec, 1 * sec, newId = ids("a3", "still")))
        assertEquals("t1", history.project.transitionAfter("a3")?.id)
        assertEquals(5 * sec, history.project.transitions.single().cutTimeUs)
    }

    @Test
    fun trimmingFitsTheDurationToTheClips() {
        val history = EditHistory(project)
        history.apply(SetTransition("L1", 4 * sec, "dissolve", 2 * sec))
        assertEquals(2 * sec, history.project.transitions.single().durationUs)
        // b is 0.3 s long now: at most 0.6 s fits.
        history.apply(TrimClip("b", Edge.End, 4_300_000))
        assertEquals(600_000, history.project.transitions.single().durationUs)
    }

    @Test
    fun anEditThatChangesNothingIsStillNotRecorded() {
        val withTransition = SetTransition("L1", 4 * sec, "dissolve").apply(project)
        val history = EditHistory(withTransition)
        history.apply(SetLayerHidden("L1", false))
        assertFalse(history.canUndo)
        assertEquals(withTransition, history.project)
    }

    @Test
    fun aTransitionTheEditPutsOnANewCutStaysThere() {
        // One undo step that makes the cut b | c and puts a transition on it.
        val history = EditHistory(project)
        history.apply(
            AllOf(MoveClip("c", 5 * sec), SetTransition("L1", 5 * sec, "flash", newId = ids("t2"))),
        )
        assertEquals("t2", history.project.transitionAfter("b")?.id)
    }

    @Test
    fun theEditsOwnTransitionWinsTheCutAnotherOneFollows() {
        // L1: x | a | b, with t1 on a | b. One step deletes x and puts t2 on a | b's new time.
        val start = project.copy(
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("x", 0, 1 * sec),
                        clip("a", 1 * sec, 4 * sec),
                        clip("b", 5 * sec, 1 * sec),
                    ),
                ),
            ),
        )
        val history = EditHistory(start)
        history.apply(SetTransition("L1", 5 * sec, "spin", newId = ids("t1")))
        history.apply(
            AllOf(DeleteClip("x"), SetTransition("L1", 4 * sec, "flash", newId = ids("t2"))),
        )
        assertEquals(listOf("t2"), history.project.transitions.map { it.id })
    }

    @Test
    fun aNewClipOfOtherMediaBeforeTheIncomingClipGetsNoTransition() {
        // As an insert would leave it: x, new and of other media, now meets b.
        val before = SetTransition("L1", 4 * sec, "dissolve").apply(project)
        val after = before.copy(
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("a", 0, 4 * sec),
                        clip("x", 4 * sec, 1 * sec),
                        clip("b", 5 * sec, 1 * sec, ClipType.Photo),
                    ),
                ),
            ),
        )
        assertEquals(emptyList<Any>(), after.keepTransitionsOnTheirCuts(before).transitions)
    }
}
