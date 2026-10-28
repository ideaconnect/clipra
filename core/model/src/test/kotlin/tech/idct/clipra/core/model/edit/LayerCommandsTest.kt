package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project

class LayerCommandsTest {
    private fun clip(id: String, start: Long, duration: Long) =
        Clip(id, ClipType.Photo, startUs = start, durationUs = duration)

    private val project = Project(
        id = "p",
        name = "Layers",
        layers = listOf(
            Layer("L1", clips = listOf(clip("a", 0, 5_000_000))),
            Layer("L2", clips = listOf(clip("b", 1_000_000, 2_000_000))),
            Layer("L3"),
        ),
    )

    private fun rejected(reason: EditRejected.Reason, command: EditCommand) {
        val error = assertThrows(EditRejected::class.java) { command.apply(project) }
        assertEquals(reason, error.reason)
    }

    @Test
    fun layersAreAddedAboveTheTop() {
        val edited = AddLayer("L4").apply(project)
        assertEquals(listOf("L1", "L2", "L3", "L4"), edited.layers.map { it.id })
    }

    @Test
    fun layersReorder() {
        val edited = MoveLayer("L1", toIndex = 2).apply(project)
        assertEquals(listOf("L2", "L3", "L1"), edited.layers.map { it.id })
        rejected(EditRejected.Reason.OutOfRange, MoveLayer("L1", toIndex = 3))
        rejected(EditRejected.Reason.LayerNotFound, MoveLayer("L9", toIndex = 0))
    }

    @Test
    fun onlyEmptyLayersAreDeleted() {
        assertEquals(listOf("L1", "L2"), DeleteLayer("L3").apply(project).layers.map { it.id })
        rejected(EditRejected.Reason.OutOfRange, DeleteLayer("L1"))
    }

    @Test
    fun hideMuteAndLockArePerLayer() {
        val edited = listOf(
            SetLayerHidden("L1", true),
            SetLayerMuted("L2", true),
            SetLayerLocked("L3", true),
        ).fold(project) { p, command -> command.apply(p) }
        assertEquals(listOf(true, false, false), edited.layers.map { it.hidden })
        assertEquals(listOf(false, true, false), edited.layers.map { it.muted })
        assertEquals(listOf(false, false, true), edited.layers.map { it.locked })
    }

    @Test
    fun movingAClipToAHigherLayerPutsItOnTop() {
        val edited = MoveClip(
            "a",
            startUs = 4_000_000,
            target = LayerTarget.Existing("L3"),
        ).apply(project)
        assertTrue(edited.layers[0].clips.isEmpty())
        assertEquals(listOf("a"), edited.layers[2].clips.map { it.id })
        assertEquals(4_000_000L, edited.layers[2].clips.single().startUs)
    }

    @Test
    fun droppingAboveTheTopLayerCreatesALayer() {
        val edited = MoveClip("b", startUs = 0, target = LayerTarget.NewOnTop("L4")).apply(project)
        assertEquals(listOf("L1", "L2", "L3", "L4"), edited.layers.map { it.id })
        assertEquals(listOf("b"), edited.layers.last().clips.map { it.id })
    }

    @Test
    fun clipsOnALayerNeverOverlap() {
        rejected(
            EditRejected.Reason.Overlap,
            MoveClip("b", startUs = 0, target = LayerTarget.Existing("L1")),
        )
        rejected(
            EditRejected.Reason.Overlap,
            AddClip(clip("c", 2_000_000, 500_000), LayerTarget.Existing("L2")),
        )
    }

    @Test
    fun lockedLayersRejectEdits() {
        val locked = SetLayerLocked("L1", true).apply(SetLayerLocked("L3", true).apply(project))
        fun rejectedOn(command: EditCommand) {
            val error = assertThrows(EditRejected::class.java) { command.apply(locked) }
            assertEquals(EditRejected.Reason.LayerLocked, error.reason)
        }
        // Moving a clip off a locked layer, onto one, or adding to one.
        rejectedOn(MoveClip("a", startUs = 6_000_000))
        rejectedOn(MoveClip("b", startUs = 0, target = LayerTarget.Existing("L3")))
        rejectedOn(AddClip(clip("c", 9_000_000, 1_000_000), LayerTarget.Existing("L1")))
        rejectedOn(DeleteLayer("L3"))
        // Hide, mute and unlock still work on a locked layer.
        assertTrue(SetLayerHidden("L1", true).apply(locked).layers[0].hidden)
    }
}
