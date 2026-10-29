package tech.idct.clipra.core.engine.render

import org.junit.Assert.assertEquals
import org.junit.Test

class ClipGainTest {
    // 4 s at 150%, fading in over 1 s and out over 2 s.
    private val gain = ClipGain(1.5f, 1_000_000, 2_000_000, 4_000_000)

    @Test
    fun fadesShapeTheVolume() {
        assertEquals(0f, gain.gainAt(0), 1e-6f)
        assertEquals(0.75f, gain.gainAt(500_000), 1e-6f)
        assertEquals(1.5f, gain.gainAt(1_500_000), 1e-6f)
        assertEquals(1.5f, gain.gainAt(2_000_000), 1e-6f)
        assertEquals(0.75f, gain.gainAt(3_000_000), 1e-6f)
        assertEquals(0f, gain.gainAt(4_000_000), 1e-6f)
    }

    @Test
    fun withoutFadesTheVolumeHolds() {
        val flat = ClipGain(0.5f, 0, 0, 4_000_000)
        assertEquals(0.5f, flat.gainAt(0), 1e-6f)
        assertEquals(0.5f, flat.gainAt(3_999_999), 1e-6f)
    }
}
