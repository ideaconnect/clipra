package tech.idct.clipra.core.engine.export

import androidx.media3.common.Metadata
import androidx.media3.container.MdtaMetadataEntry
import androidx.media3.container.Mp4LocationData
import androidx.media3.container.Mp4TimestampData
import androidx.media3.container.XmpData
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant

class ExportMetadataTest {
    @Test
    fun theExportKeepsOnlyItsOwnCreationTime() {
        val exportedAt = Instant.parse("2026-11-06T18:30:15Z").toEpochMilli()
        val firstClip = mutableSetOf<Metadata.Entry>(
            // The phone's recording: 2025-09-13 13:39:52 UTC, in seconds since 1904.
            Mp4TimestampData(3_840_615_592, 3_840_615_592),
            MdtaMetadataEntry(
                "com.android.model",
                "25010PN30G".toByteArray(),
                MdtaMetadataEntry.TYPE_INDICATOR_STRING,
            ),
            MdtaMetadataEntry(
                "com.xiaomi.product.marketname",
                "Xiaomi 15 Ultra".toByteArray(),
                MdtaMetadataEntry.TYPE_INDICATOR_STRING,
            ),
            Mp4LocationData(52.2297f, 21.0122f),
            XmpData("<x:xmpmeta/>".toByteArray()),
        )

        ExportMetadata { exportedAt }.updateMetadataEntries(firstClip)

        // 2026-11-06 18:30:15 UTC: 1793989815 s since 1970, plus 66 years and 17 leap days
        // (2082844800 s) since 1904, where MP4 times start.
        val seconds = 1_793_989_815L + 2_082_844_800L
        assertEquals(setOf(Mp4TimestampData(seconds, seconds)), firstClip)
    }

    @Test
    fun anExportOfClipsWithoutMetadataIsDatedToo() {
        val entries = mutableSetOf<Metadata.Entry>()

        ExportMetadata { 1_000L }.updateMetadataEntries(entries)

        assertEquals(setOf(Mp4TimestampData(2_082_844_801, 2_082_844_801)), entries)
    }
}
