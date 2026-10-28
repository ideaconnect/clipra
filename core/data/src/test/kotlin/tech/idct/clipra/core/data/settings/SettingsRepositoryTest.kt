package tech.idct.clipra.core.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
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
}
