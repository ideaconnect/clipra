package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

class MicrosecondsTest {
    @Test
    fun convertsSecondsBothWays() {
        // The design's demo project is 42 s long.
        assertEquals(42_000_000L, Microseconds.fromSeconds(42.0))
        assertEquals(42.0, Microseconds.toSeconds(42_000_000L), 0.0)
        // One frame at 30 fps rounds to the nearest microsecond.
        assertEquals(33_333L, Microseconds.fromSeconds(1.0 / 30))
    }
}
