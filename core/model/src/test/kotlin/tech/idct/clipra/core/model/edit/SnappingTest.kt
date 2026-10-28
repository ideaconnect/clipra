package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Project

class SnappingTest {
    private val sec = 1_000_000L
    private val threshold = 150_000L

    private val project = Project(
        id = "p",
        name = "Snap",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("a", ClipType.Photo, startUs = 0, durationUs = 2 * sec),
                    Clip("b", ClipType.Photo, startUs = 3 * sec, durationUs = 2 * sec),
                ),
            ),
        ),
    )

    @Test
    fun targetsAreThePlayheadCutsAndBeatsButNotTheDraggedClip() {
        val targets = Snapping.targets(
            project,
            playheadUs = 7 * sec,
            excludeClipId = "b",
            beatsUs = listOf(6 * sec),
        )
        assertEquals(listOf(0L, 2 * sec, 6 * sec, 7 * sec), targets)
    }

    @Test
    fun snapsWithinTheThresholdOnly() {
        val targets = listOf(0L, 2 * sec, 7 * sec)
        assertEquals(2 * sec, Snapping.snap(2 * sec + 100_000, targets, threshold))
        assertEquals(2 * sec + 200_000, Snapping.snap(2 * sec + 200_000, targets, threshold))
    }

    @Test
    fun aMovedClipSnapsByWhicheverEdgeIsNearer() {
        val targets = listOf(0L, 2 * sec, 7 * sec)
        // Start 2.1 s is 0.1 s from the cut at 2 s; the end 4.1 s is near nothing.
        assertEquals(2 * sec, Snapping.snapMove(2 * sec + 100_000, 2 * sec, targets, threshold))
        // End 6.95 s is 0.05 s from the playhead at 7 s.
        assertEquals(5 * sec, Snapping.snapMove(4 * sec + 950_000, 2 * sec, targets, threshold))
        // Nothing close: unchanged.
        assertEquals(
            3 * sec + 500_000,
            Snapping.snapMove(3 * sec + 500_000, 2 * sec, targets, threshold),
        )
    }
}
