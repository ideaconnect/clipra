package tech.idct.clipra.feature.export

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.engine.encoder.VideoEncoders
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import tech.idct.clipra.monetization.ads.ExportAds
import tech.idct.clipra.monetization.billing.ProStore
import javax.inject.Inject

data class ExportUiState(
    val project: Project? = null,
    val settings: ExportSettings = ExportSettings(),
    val support: EncoderSupport = EncoderSupport(),
    /** Clipra Pro: no ads, so no line about them. */
    val pro: Boolean = false,
    /** Clipra Pro's price from Google Play, for "Remove for"; null until Play answers. */
    val proPrice: String? = null,
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    private val projects: ProjectRepository,
    private val proStore: ProStore,
    exportAds: ExportAds,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState.get<String>("projectId")
    private val mutableState = MutableStateFlow(ExportUiState())

    val state: StateFlow<ExportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val project = projectId?.let { projects.load(it) } ?: return@launch
            val wanted = project.exportSettings ?: ExportSettings.forProject(project.format)
            mutableState.update { it.copy(project = project) }
            change(wanted)
        }
        // Ad 1 loads while the settings are chosen, so it is not late (rule 10).
        viewModelScope.launch { exportAds.prepare() }
        viewModelScope.launch { proStore.loadPrice() }
        viewModelScope.launch {
            combine(proStore.isPro, proStore.price, ::Pair).collect { (pro, price) ->
                mutableState.update { it.copy(pro = pro, proPrice = price) }
            }
        }
    }

    /** Applies [settings], falling back to what this phone can encode. */
    fun change(settings: ExportSettings) {
        val project = state.value.project ?: return
        viewModelScope.launch {
            val (fitted, support) = withContext(Dispatchers.Default) { fit(project, settings) }
            mutableState.update { it.copy(settings = fitted, support = support) }
        }
    }

    /** Remembers the settings in the project and hands over to the export (#58). */
    fun start(onStarted: (projectId: String) -> Unit) {
        val project = state.value.project ?: return
        viewModelScope.launch {
            projects.save(project.copy(exportSettings = state.value.settings))
            onStarted(project.id)
        }
    }

    private fun fit(
        project: Project,
        settings: ExportSettings,
    ): Pair<ExportSettings, EncoderSupport> {
        fun encodable(s: ExportSettings): Boolean {
            val size = s.outputSize(project)
            return VideoEncoders.supportsSize(size.width, size.height, s.codec.mimeType)
        }
        val codec = settings.codec.takeIf { encodable(settings) } ?: VideoCodec.H264
        var fitted = settings.copy(codec = codec)
        while (!encodable(fitted) && fitted.resolution.ordinal > 0) {
            fitted = fitted.copy(resolution = Resolution.entries[fitted.resolution.ordinal - 1])
        }
        val support = EncoderSupport(
            codecs = VideoCodec.entries.filter { encodable(fitted.copy(codec = it)) }.toSet(),
            resolutions = Resolution.entries.filter {
                encodable(fitted.copy(resolution = it))
            }.toSet(),
        )
        return fitted to support
    }
}
