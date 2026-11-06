package tech.idct.clipra.core.engine.media

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

/** The dates that describe a missing file (#216), as phones write them into media. */
class MediaInspectorTest {
    @Test
    fun aVideosContainerDateIsInUtc() {
        // 2026-11-05 14:30:00 UTC.
        assertEquals(1_793_889_000_000, MediaInspector.containerDate("20261105T143000.000Z"))
        assertEquals(1_793_889_000_000, MediaInspector.containerDate("20261105T143000Z"))
    }

    @Test
    fun aDateThatWasNeverSetIsUnknown() {
        assertEquals(0L, MediaInspector.containerDate(null))
        assertEquals(0L, MediaInspector.containerDate(""))
        // The MP4 zero, and Unix's.
        assertEquals(0L, MediaInspector.containerDate("19040101T000000.000Z"))
        assertEquals(0L, MediaInspector.containerDate("19700101T000000.000Z"))
        assertEquals(0L, MediaInspector.containerDate("not a date"))
        assertEquals(0L, MediaInspector.exifDate("0000:00:00 00:00:00", null))
        assertEquals(0L, MediaInspector.exifDate(null, null))
    }

    @Test
    fun aPhotosExifDateIsLocalTimeUnlessItHasAnOffset() {
        val warsaw = ZoneId.of("Europe/Warsaw")
        // 14:30 in Warsaw in November is 13:30 UTC.
        assertEquals(
            1_793_885_400_000,
            MediaInspector.exifDate("2026:11:05 14:30:00", null, warsaw),
        )
        // With the offset the camera wrote, the zone doesn't matter.
        assertEquals(
            1_793_889_000_000,
            MediaInspector.exifDate("2026:11:05 15:30:00", "+01:00", ZoneId.of("Asia/Tokyo")),
        )
        // A broken offset falls back to the zone.
        assertEquals(
            1_793_885_400_000,
            MediaInspector.exifDate("2026:11:05 14:30:00", "local", warsaw),
        )
    }
}
