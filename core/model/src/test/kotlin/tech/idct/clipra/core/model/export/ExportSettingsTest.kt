package tech.idct.clipra.core.model.export

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.Size
import java.util.Locale

/** The size estimate of design/canvas/Export.dc.html. */
class ExportSettingsTest {
    @Test
    fun theDesignsDefaultIs12MbpsAnd64MbFor42Seconds() {
        val settings = ExportSettings()
        assertEquals(12.0, settings.videoMbps, 0.0001)
        assertEquals("~64.3", formatEstimate(settings.estimatedMegabytes(42_000_000), Locale.US))
    }

    @Test
    fun theDesigns4kHevcHighState() {
        // 45 × 0.6 (HEVC) × 1.6 (High) = 43.2 Mbps; 42 s gives 226.8 + 1.3 = 228 MB.
        val settings =
            ExportSettings(
                resolution = Resolution.UHD,
                codec = VideoCodec.HEVC,
                quality = ExportQuality.High,
            )
        assertEquals(43.2, settings.videoMbps, 0.0001)
        assertEquals("~228", formatEstimate(settings.estimatedMegabytes(42_000_000), Locale.US))
    }

    @Test
    fun framesAbove30AndCodecsScaleTheBitrate() {
        assertEquals(18.0, ExportSettings(frameRate = FrameRate.Fps60).videoMbps, 0.0001)
        assertEquals(12.0, ExportSettings(frameRate = FrameRate.Fps30).videoMbps, 0.0001)
        assertEquals(6.0, ExportSettings(codec = VideoCodec.AV1).videoMbps, 0.0001)
        assertEquals(
            4.5,
            ExportSettings(resolution = Resolution.HD, quality = ExportQuality.Smaller).videoMbps,
            0.0001,
        )
        assertEquals(12_000_000, ExportSettings().videoBitrate)
    }

    @Test
    fun theOutputKeepsTheProjectRatio() {
        val project =
            Project(id = "p", name = "p", format = Format(ratio = AspectRatio.Landscape16x9))
        assertEquals(
            Size(3840, 2160),
            ExportSettings(resolution = Resolution.UHD).outputSize(project),
        )
        assertEquals(
            Size(1280, 720),
            ExportSettings(resolution = Resolution.HD).outputSize(project),
        )
    }

    @Test
    fun settingsStartFromTheProject() {
        val settings = ExportSettings.forProject(
            Format(resolution = Resolution.QHD, frameRate = FrameRate.Fps24),
        )
        assertEquals(Resolution.QHD, settings.resolution)
        assertEquals(FrameRate.Fps24, settings.frameRate)
    }
}
