package tech.idct.clipra.core.designsystem.component

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FrameRateMeterTest {
    private val second = 1_000_000_000L

    @Test
    fun steadyFramesGiveTheirRate() {
        for (rate in listOf(24, 25, 30, 50, 60)) {
            val meter = FrameRateMeter()
            val frameNs = second / rate
            var now = 0L
            repeat(rate * 3) {
                now += frameNs
                meter.onFrame(now)
            }
            assertEquals("$rate fps", rate, meter.fps(now + frameNs / 2))
        }
    }

    @Test
    fun onlyTheLastSecondCounts() {
        val meter = FrameRateMeter()
        var now = 0L
        // Two seconds at 60, then one at 30.
        repeat(120) {
            now += second / 60
            meter.onFrame(now)
        }
        repeat(30) {
            now += second / 30
            meter.onFrame(now)
        }
        assertEquals(30, meter.fps(now))
    }

    @Test
    fun aStillPictureHasNoRate() {
        val meter = FrameRateMeter()
        assertNull(meter.fps(0))
        meter.onFrame(0)
        // One redraw of a paused frame is not a rate.
        assertNull(meter.fps(second / 60))
        var now = 0L
        repeat(60) {
            now += second / 60
            meter.onFrame(now)
        }
        assertEquals(60, meter.fps(now + second / 4))
        // Paused: the last picture is over half a second old.
        assertNull(meter.fps(now + second * 6 / 10))
        assertNull(meter.fps(now + second * 2))
    }

    @Test
    fun dropsAndHiccupsLowerTheRate() {
        val meter = FrameRateMeter()
        var now = 0L
        // 60 fps with every other frame missing.
        repeat(60) {
            now += second / 30
            meter.onFrame(now)
        }
        assertEquals(30, meter.fps(now))
    }
}
