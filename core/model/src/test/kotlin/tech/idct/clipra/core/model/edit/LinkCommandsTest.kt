package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project

/** The Linked tool (#184): linking and unlinking clips, which then move apart or together. */
class LinkCommandsTest {
    private val sec = 1_000_000L

    private fun clip(id: String, start: Long, type: ClipType = ClipType.Video) =
        Clip(id, type, start, 2 * sec, MediaRef("m", 0, 2 * sec))

    // L1: a video and its detached sound (linked) on L2; a music clip on L3, on its own.
    private val project = Project(
        id = "p",
        name = "p",
        layers = listOf(
            Layer("L1", clips = listOf(clip("video", 0))),
            Layer("L2", clips = listOf(clip("sound", 0, ClipType.Audio))),
            Layer("L3", clips = listOf(clip("music", 4 * sec, ClipType.Audio))),
        ),
        links = listOf(LinkGroup("g", listOf("video", "sound"))),
    )

    private fun applyAndCheckInverse(command: EditCommand, from: Project = project): Project {
        val history = EditHistory(from)
        val edited = history.apply(command)
        assertEquals(from, history.undo())
        assertEquals(edited, history.redo())
        return edited
    }

    @Test
    fun anUnlinkedClipMovesOnItsOwn() {
        val unlinked = applyAndCheckInverse(UnlinkClip("sound"))
        assertTrue(unlinked.links.isEmpty())
        val moved = MoveClip("video", 1 * sec).apply(unlinked)
        assertEquals(0L, moved.findClip("sound")!!.second.startUs)
    }

    @Test
    fun linkingJoinsTheClipsEitherIsLinkedWith() {
        val linked = applyAndCheckInverse(LinkClips("music", "video", newId = { "new" }))
        // One group, the existing one, with all three.
        assertEquals(1, linked.links.size)
        assertEquals("g", linked.links.single().id)
        assertEquals(setOf("video", "sound", "music"), linked.links.single().clipIds.toSet())
        val moved = MoveClip("video", 1 * sec).apply(linked)
        assertEquals(5 * sec, moved.findClip("music")!!.second.startUs)

        // Two clips linked with nothing make a new group.
        val alone = UnlinkClip("sound").apply(project)
        val paired = LinkClips("music", "sound", newId = { "new" }).apply(alone)
        assertEquals(listOf(LinkGroup("new", listOf("music", "sound"))), paired.links)
        assertThrows(EditRejected::class.java) { LinkClips("video", "video").apply(project) }
    }
}
