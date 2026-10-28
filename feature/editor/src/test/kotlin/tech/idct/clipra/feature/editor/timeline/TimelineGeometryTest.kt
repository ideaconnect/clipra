package tech.idct.clipra.feature.editor.timeline

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import java.util.Locale

/** The Editor board's geometry: 32 dp per second, playhead fixed at the centre. */
class TimelineGeometryTest {
    // The phone board is 390 dp wide; the playhead sits at 195.
    private val geometry = TimelineGeometry(playheadX = 195f, playheadUs = 7_120_000)

    @Test
    fun positionsFollowThePlayhead() {
        assertEquals(195f, geometry.x(7_120_000), 0.01f)
        // x(t) = 195 + (t - playhead) * 32: 2 s later is 64 dp to the right.
        assertEquals(259f, geometry.x(9_120_000), 0.01f)
        assertEquals(-32.84f, geometry.x(0), 0.01f)
        assertEquals(294.4f, geometry.width(9_200_000), 0.01f)
    }

    @Test
    fun dragsAndTapsConvertBack() {
        assertEquals(9_120_000L, geometry.time(259f))
        assertEquals(500_000L, geometry.duration(16f))
    }

    @Test
    fun rulerTicksEveryHalfSecondWithLabelsEveryTwo() {
        val ticks = geometry.ticks(viewWidth = 390f)
        val visible = ticks.filter { it.x in 0f..390f }
        assertEquals(500_000L, visible[1].timeUs - visible[0].timeUs)
        assertEquals(
            listOf(2_000_000L, 4_000_000L, 6_000_000L, 8_000_000L, 10_000_000L, 12_000_000L),
            visible.filter { it.labelled }.map { it.timeUs },
        )
        assertEquals(true, ticks.first { it.timeUs == 4_000_000L }.long)
        assertEquals(false, ticks.first { it.timeUs == 4_500_000L }.long)
    }

    @Test
    fun videoLanesAreTall() {
        val video = Layer("L1", clips = listOf(Clip("v", ClipType.Video, 0, 1_000_000)))
        val text = Layer("L2", clips = listOf(Clip("t", ClipType.Text, 0, 1_000_000)))
        assertEquals(50f, TimelineGeometry.laneHeight(video))
        assertEquals(28f, TimelineGeometry.laneHeight(text))
    }

    @Test
    fun timeFormats() {
        assertEquals("00:07.12", formatTimecode(7_120_000))
        assertEquals("00:42.00", formatTimecode(42_000_000))
        assertEquals("01:05.50", formatTimecode(65_500_000))
        assertEquals("00:04", formatRulerLabel(4_000_000))
        assertEquals("9.2s", formatClipDuration(9_200_000, Locale.US))
        assertEquals("9,2s", formatClipDuration(9_200_000, Locale.forLanguageTag("pl")))
    }
}
