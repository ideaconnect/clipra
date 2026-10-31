package tech.idct.clipra.feature.export

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.data.settings.SettingsRepository
import tech.idct.clipra.core.engine.encoder.VideoEncoders
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.engine.render.StillRenderer
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.export.ExportSettings
import tech.idct.clipra.core.model.export.VideoCodec
import tech.idct.clipra.monetization.ads.ExportAds
import tech.idct.clipra.monetization.billing.ProStore
import javax.inject.Inject
import kotlin.math.roundToInt

data class ExportUiState(
    val project: Project? = null,
    val settings: ExportSettings = ExportSettings(),
    val support: EncoderSupport = EncoderSupport(),
    /** Clipra Pro: no ads, so no line about them. */
    val pro: Boolean = false,
    /** Clipra Pro's price from Google Play, for "Remove for"; null until Play answers. */
    val proPrice: String? = null,
    /**
     * The cover frame, in the phone's header and the tablet's preview, and the frames along the
     * project, which the tablet shows (#70).
     */
    val cover: Bitmap? = null,
    val strip: List<Bitmap> = emptyList(),
)

@HiltViewModel
class ExportViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
    private val projects: ProjectRepository,
    private val proStore: ProStore,
    private val settings: SettingsRepository,
    exportAds: ExportAds,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState.get<String>("projectId")
    private val playheadUs: Long = savedState.get<Long>("playheadUs") ?: 0L
    private val mutableState = MutableStateFlow(ExportUiState())
    private val stills by lazy { StillRenderer(context) }
    private var previewJob: Job? = null
    private var coverJob: Job? = null

    val state: StateFlow<ExportUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val project = projectId?.let { projects.load(it) } ?: return@launch
            // A project's first export starts from the defaults in Settings (#74).
            val wanted = project.exportSettings ?: settings.exportDefaults.first().copy(
                resolution = project.format.resolution,
                frameRate = project.format.frameRate,
            )
            mutableState.update { it.copy(project = project) }
            // The cover is the frame at the playhead (#59).
            change(wanted.copy(coverUs = playheadUs))
            drawCover(playheadUs)
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

    /**
     * Draws [count] frames along the project from the proxies, for the tablet's Export (#70);
     * the phone has no room for them.
     */
    fun loadPreview(count: Int) {
        if (previewJob != null) return
        val project = state.value.project ?: return
        previewJob = viewModelScope.launch {
            val strip = (0 until count).mapNotNull { i ->
                frame(project, project.durationUs * (2 * i + 1) / (2 * count), STRIP_SIDE)
            }
            mutableState.update { it.copy(strip = strip) }
        }
    }

    /** Makes the frame at [atUs] the cover (#59), as a tap on the tablet's filmstrip does. */
    fun setCover(atUs: Long) {
        change(state.value.settings.copy(coverUs = atUs))
        drawCover(atUs)
    }

    private fun drawCover(atUs: Long = state.value.settings.coverUs) {
        val project = state.value.project ?: return
        coverJob?.cancel()
        coverJob = viewModelScope.launch {
            val cover = frame(project, atUs, COVER_SIDE)
            mutableState.update { it.copy(cover = cover) }
        }
    }

    /** [project]'s frame at [atUs] from the proxies, its long side [side] px. */
    private suspend fun frame(project: Project, atUs: Long, side: Int): Bitmap? = try {
        val output = project.format.outputSize
        val scale = side.toFloat() / maxOf(output.width, output.height)
        val width = (output.width * scale / 2).roundToInt() * 2
        val height = (output.height * scale / 2).roundToInt() * 2
        val timeline = withContext(Dispatchers.Default) {
            ProjectRender.timeline(
                project,
                ProjectMediaResolver(context, cache, forExport = false, maxImageSide = side),
                width,
                height,
            )
        }
        stills.render(timeline, atUs.coerceIn(0, maxOf(0, project.durationUs - 1)))
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override fun onCleared() = stills.release()

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

    private companion object {
        const val COVER_SIDE = 1280
        const val STRIP_SIDE = 160
    }
}
