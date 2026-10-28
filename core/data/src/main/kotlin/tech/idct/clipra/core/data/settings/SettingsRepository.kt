package tech.idct.clipra.core.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

/** App settings, kept in DataStore. */
class SettingsRepository @Inject constructor(private val dataStore: DataStore<Preferences>) {
    /** Download AI models on Wi-Fi only. On by default (docs/product/spec.md, section 18). */
    val wifiOnlyDownloads: Flow<Boolean> = dataStore.data.map { it[WIFI_ONLY_DOWNLOADS] ?: true }

    suspend fun setWifiOnlyDownloads(enabled: Boolean) {
        dataStore.edit { it[WIFI_ONLY_DOWNLOADS] = enabled }
    }

    private companion object {
        val WIFI_ONLY_DOWNLOADS = booleanPreferencesKey("wifi_only_downloads")
    }
}
