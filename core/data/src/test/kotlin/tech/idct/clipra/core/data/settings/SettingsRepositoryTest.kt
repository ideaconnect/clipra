package tech.idct.clipra.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.export.CaptionMode
import tech.idct.clipra.core.model.export.ExportQuality
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import java.io.File

class SettingsRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun wifiOnlyDownloadsIsOnByDefaultAndCanBeTurnedOff() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(folder.root, "settings.preferences_pb")
        }
        val settings = SettingsRepository(dataStore)

        assertTrue(settings.wifiOnlyDownloads.first())
        settings.setWifiOnlyDownloads(false)
        assertFalse(settings.wifiOnlyDownloads.first())
    }

    @Test
    fun exportDefaultsKeepCodecQualityAndCaptions() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(folder.root, "settings.preferences_pb")
        }
        val settings = SettingsRepository(dataStore)

        assertEquals(ExportSettings(), settings.exportDefaults.first())
        settings.setExportDefaults(
            ExportSettings(
                resolution = Resolution.UHD,
                codec = VideoCodec.HEVC,
                quality = ExportQuality.High,
                captions = CaptionMode.Srt,
            ),
        )
        // The resolution and frame rate follow each project, so they are not kept.
        assertEquals(
            ExportSettings(
                codec = VideoCodec.HEVC,
                quality = ExportQuality.High,
                captions = CaptionMode.Srt,
            ),
            settings.exportDefaults.first(),
        )
    }

    @Test
    fun onboardingIsDoneOnce() = runTest {
        val dataStore = PreferenceDataStoreFactory.create(scope = backgroundScope) {
            File(folder.root, "settings.preferences_pb")
        }
        val settings = SettingsRepository(dataStore)

        assertFalse(settings.onboarded.first())
        settings.setOnboarded()
        assertTrue(settings.onboarded.first())
    }
}
