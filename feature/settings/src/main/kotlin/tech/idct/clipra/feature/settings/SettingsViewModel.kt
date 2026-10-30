package tech.idct.clipra.feature.settings

import android.app.Activity
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.settings.SettingsRepository
import tech.idct.clipra.core.engine.encoder.VideoEncoders
import tech.idct.clipra.core.engine.export.ExportManager
import tech.idct.clipra.core.engine.export.ExportState
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import tech.idct.clipra.core.models.ModelManager
import tech.idct.clipra.core.models.ModelStatus
import tech.idct.clipra.monetization.ads.AdConsent
import tech.idct.clipra.monetization.billing.ProStore
import tech.idct.clipra.monetization.billing.RestoreResult
import javax.inject.Inject

/** What Settings shows (#74). */
data class SettingsUiState(
    val pro: Boolean = false,
    /** Clipra Pro's price from Google Play; null until Play answers. */
    val proPrice: String? = null,
    val restoring: Boolean = false,
    val restore: RestoreResult? = null,
    val exportDefaults: ExportSettings = ExportSettings(),
    /** The codecs this phone encodes at 1080p; the others are offered disabled. */
    val codecs: Set<VideoCodec> = setOf(VideoCodec.H264),
    /** Proxies and caches; null while measuring. */
    val cacheBytes: Long? = null,
    /** An export reads the cache, so it is not cleared while one runs. */
    val exporting: Boolean = false,
    val modelBytes: Long = 0,
    val adPrivacyChoices: Boolean = false,
    val version: String = "",
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val cache: MediaCache,
    private val proStore: ProStore,
    private val consent: AdConsent,
    exports: ExportManager,
    models: ModelManager,
) : ViewModel() {
    private val local = MutableStateFlow(SettingsUiState(version = version()))

    val state: StateFlow<SettingsUiState> = combine(
        local,
        combine(proStore.isPro, proStore.price, ::Pair),
        settings.exportDefaults,
        combine(exports.state, consent.state) { export, consent ->
            (export is ExportState.Running) to consent.privacyOptionsRequired
        },
        models.models.map { list ->
            list.sumOf { (it.status as? ModelStatus.Installed)?.bytes ?: 0L }
        },
    ) { local, (pro, price), defaults, (exporting, privacy), modelBytes ->
        local.copy(
            pro = pro,
            proPrice = price,
            exportDefaults = defaults,
            exporting = exporting,
            adPrivacyChoices = privacy,
            modelBytes = modelBytes,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_MS), local.value)

    init {
        measureCache()
        viewModelScope.launch {
            val codecs = withContext(Dispatchers.Default) {
                VideoCodec.entries.filter { VideoEncoders.supportsSize(1080, 1920, it.mimeType) }
            }
            local.value = local.value.copy(codecs = codecs.toSet() + VideoCodec.H264)
        }
        viewModelScope.launch { proStore.loadPrice() }
    }

    fun setExportDefaults(defaults: ExportSettings) {
        viewModelScope.launch { settings.setExportDefaults(defaults) }
    }

    fun restore() {
        if (local.value.restoring) return
        viewModelScope.launch {
            local.value = local.value.copy(restoring = true, restore = null)
            val result = proStore.refresh()
            proStore.loadPrice()
            local.value = local.value.copy(restoring = false, restore = result)
        }
    }

    fun clearCache() {
        if (state.value.exporting) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { cache.clear() }
            measureCache()
        }
    }

    fun adPrivacy(activity: Activity) {
        viewModelScope.launch { consent.showPrivacyOptions(activity) }
    }

    private fun measureCache() {
        viewModelScope.launch {
            val bytes = withContext(Dispatchers.IO) { cache.sizeBytes() }
            local.value = local.value.copy(cacheBytes = bytes)
        }
    }

    private fun version(): String {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        return "${info.versionName} (${info.longVersionCode})"
    }

    private companion object {
        const val STOP_MS = 5_000L
    }
}
