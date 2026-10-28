package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import tech.idct.clipra.core.model.TextSpec

class ClipCommandsTest {
    private val sec = 1_000_000L

    private fun ids(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    private fun video(id: String, start: Long, duration: Long, inPoint: Long = 0) = Clip(
        id = id,
        type = ClipType.Video,
        startUs = start,
        durationUs = duration,
        source = MediaRef("sunset", inPointUs = inPoint, outPointUs = inPoint + duration),
    )

    // L1: sunset (0-4 s, media 1-5 s) then tram (4-6 s). L2: a caption linked to sunset.
    // L3: music.
    private val project = Project(
        id = "p",
        name = "Lisbon weekend",
        media = listOf(
            MediaAsset("sunset", "content://1", MediaKind.Video, durationUs = 10 * sec),
            MediaAsset("tram", "content://2", MediaKind.Video, durationUs = 3 * sec),
            MediaAsset("stamp", "content://3", MediaKind.Photo),
        ),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    video("sunset", 0, 4 * sec, inPoint = 1 * sec),
                    video("tram", 4 * sec, 2 * sec).copy(source = MediaRef("tram", 0, 2 * sec)),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    Clip(
                        "cap",
                        ClipType.Caption,
                        startUs = 1 * sec,
                        durationUs = 2 * sec,
                        text = TextSpec("We made it"),
                    ),
                ),
            ),
            Layer("L3"),
        ),
        links = listOf(LinkGroup("g1", listOf("sunset", "cap"))),
    )

    /** Applies through the history and checks that undo and redo give back both projects. */
    private fun applyAndCheckInverse(command: EditCommand): Project {
        val history = EditHistory(project)
        val edited = history.apply(command)
        assertTrue(history.canUndo)
        assertEquals(project, history.undo())
        assertTrue(history.canRedo)
        assertEquals(edited, history.redo())
        return edited
    }

    private fun Project.clip(id: String) = findClip(id)!!.second

    private fun Project.layerOf(id: String) = findClip(id)!!.first.id

    @Test
    fun splitAtThePlayheadCutsTheSourceAndTheLinkedCaption() {
        val edited =
            applyAndCheckInverse(
                SplitClip("sunset", atUs = 2 * sec, newId = ids("sunset-2", "cap-2", "g2")),
            )

        assertEquals(2 * sec, edited.clip("sunset").durationUs)
        assertEquals(MediaRef("sunset", 1 * sec, 3 * sec), edited.clip("sunset").source)
        assertEquals(2 * sec, edited.clip("sunset-2").startUs)
        assertEquals(MediaRef("sunset", 3 * sec, 5 * sec), edited.clip("sunset-2").source)
        // The caption is cut with its source, and the right halves are linked to each other.
        assertEquals(1 * sec, edited.clip("cap").durationUs)
        assertEquals(2 * sec, edited.clip("cap-2").startUs)
        assertEquals(setOf("cap-2"), edited.linkedPartners("sunset-2"))
        assertEquals(setOf("cap"), edited.linkedPartners("sunset"))
    }

    @Test
    fun splitsTooCloseToAnEdgeAreRefused() {
        assertThrows(EditRejected::class.java) { SplitClip("sunset", atUs = 10_000).apply(project) }
    }

    @Test
    fun movingAClipMovesItsLinkedCaption() {
        val edited = applyAndCheckInverse(MoveClip("sunset", startUs = 7 * sec))
        assertEquals(7 * sec, edited.clip("sunset").startUs)
        assertEquals(8 * sec, edited.clip("cap").startUs)
        assertEquals("L2", edited.layerOf("cap"))
    }

    @Test
    fun rippleDeleteClosesTheGapAndTakesTheLinkedCaption() {
        val edited = applyAndCheckInverse(DeleteClip("sunset"))
        assertEquals(null, edited.findClip("sunset"))
        assertEquals(null, edited.findClip("cap"))
        assertEquals(0L, edited.clip("tram").startUs)
        assertTrue(edited.links.isEmpty())
    }

    @Test
    fun deleteWithoutRippleLeavesTheGap() {
        val edited = applyAndCheckInverse(DeleteClip("tram", ripple = false))
        assertEquals(null, edited.findClip("tram"))
        assertEquals(4 * sec, edited.clip("sunset").durationUs)
    }

    @Test
    fun duplicateGoesAfterTheClipOrOnANewLayer() {
        val afterTram = applyAndCheckInverse(DuplicateClip("tram", newId = ids("tram-copy")))
        assertEquals(6 * sec, afterTram.clip("tram-copy").startUs)
        assertEquals("L1", afterTram.layerOf("tram-copy"))

        // Right after sunset is tram, so the copy goes on a new top layer at the same time.
        val onTop = applyAndCheckInverse(DuplicateClip("sunset", newId = ids("sunset-copy", "L4")))
        assertEquals("L4", onTop.layerOf("sunset-copy"))
        assertEquals(0L, onTop.clip("sunset-copy").startUs)
    }

    @Test
    fun trimTakesAndGivesBackMedia() {
        val start = applyAndCheckInverse(TrimClip("sunset", Edge.Start, toUs = 1 * sec))
        assertEquals(1 * sec, start.clip("sunset").startUs)
        assertEquals(MediaRef("sunset", 2 * sec, 5 * sec), start.clip("sunset").source)

        val end = applyAndCheckInverse(TrimClip("tram", Edge.End, toUs = 5 * sec))
        assertEquals(1 * sec, end.clip("tram").durationUs)

        // Past the media, or into the neighbour: refused.
        assertThrows(EditRejected::class.java) {
            TrimClip("tram", Edge.End, toUs = 8 * sec).apply(project)
        }
        assertThrows(EditRejected::class.java) {
            TrimClip("sunset", Edge.End, toUs = 5 * sec).apply(project)
        }
    }

    @Test
    fun freezeFrameHoldsAFrameAndPushesTheRest() {
        val edited = applyAndCheckInverse(
            FreezeFrame(
                "sunset",
                atUs = 2 * sec,
                durationUs = 1 * sec,
                newId = ids("sunset-2", "still"),
            ),
        )
        val still = edited.clip("still")
        assertEquals(2 * sec, still.startUs)
        assertEquals(0.0, still.speed.rate, 0.0)
        assertEquals(MediaRef("sunset", 3 * sec, 3 * sec), still.source)
        assertEquals(3 * sec, edited.clip("sunset-2").startUs)
        assertEquals(5 * sec, edited.clip("tram").startUs)
    }

    @Test
    fun extractAudioDetachesALinkedSoundClip() {
        val edited = applyAndCheckInverse(ExtractAudio("tram", newId = ids("tram-audio", "g2")))
        val audio = edited.clip("tram-audio")
        assertEquals(ClipType.Audio, audio.type)
        assertEquals(4 * sec, audio.startUs)
        // L2 is free at 4-6 s, so the sound goes there.
        assertEquals("L2", edited.layerOf("tram-audio"))
        assertEquals(0f, edited.clip("tram").audio.volume)
        assertEquals(setOf("tram-audio"), edited.linkedPartners("tram"))
    }

    @Test
    fun replaceMediaKeepsThePlaceAndFitsTheLength() {
        val edited = applyAndCheckInverse(ReplaceMedia("sunset", "tram"))
        val clip = edited.clip("sunset")
        assertEquals(0L, clip.startUs)
        assertEquals(3 * sec, clip.durationUs)
        assertEquals(MediaRef("tram", 0, 3 * sec), clip.source)

        val photo = applyAndCheckInverse(ReplaceMedia("sunset", "stamp"))
        assertEquals(ClipType.Photo, photo.clip("sunset").type)
        assertEquals(4 * sec, photo.clip("sunset").durationUs)
    }

    @Test
    fun aRejectedEditChangesNothingAndIsNotRecorded() {
        val history = EditHistory(SetLayerLocked("L1", true).apply(project))
        assertThrows(EditRejected::class.java) { history.apply(DeleteClip("tram")) }
        assertFalse(history.canUndo)
    }

    @Test
    fun undoAndRedoWalkTheWholeSession() {
        val history = EditHistory(project)
        history.apply(MoveClip("tram", startUs = 6 * sec))
        history.apply(SplitClip("sunset", atUs = 2 * sec, newId = ids("s2", "c2", "g2")))
        history.apply(AddLayer("L4"))
        repeat(3) { history.undo() }
        assertEquals(project, history.project)
        assertFalse(history.canUndo)
        repeat(3) { history.redo() }
        assertEquals(4, history.project.layers.size)
        assertFalse(history.canRedo)
        // A new edit after undo drops the redo steps.
        history.undo()
        history.apply(SetLayerMuted("L1", true))
        assertFalse(history.canRedo)
    }
}
