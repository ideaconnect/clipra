package tech.idct.clipra.feature.effects.filters

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.Thumbnails
import tech.idct.clipra.core.engine.render.RenderClip
import tech.idct.clipra.core.engine.render.RenderLayer
import tech.idct.clipra.core.engine.render.RenderSource
import tech.idct.clipra.core.engine.render.RenderTimeline
import tech.idct.clipra.core.engine.render.StillRenderer
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.edit.ApplyFilterToAll
import tech.idct.clipra.core.model.edit.SetFilter
import tech.idct.clipra.core.model.filter
import tech.idct.clipra.feature.effects.common.THUMBNAIL_GRID
import tech.idct.clipra.feature.effects.common.centreCrop
import tech.idct.clipra.feature.effects.common.gridCell
import javax.inject.Inject

/** Which filters the grid shows. */
enum class FilterCategory { All, Looks, Stylized }

/** What the Filters screen shows. [thumbnails] are keyed by filter id, "" for None. */
data class FiltersUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
    val category: FilterCategory = FilterCategory.All,
    val thumbnails: Map<String, Bitmap> = emptyMap(),
)

@HiltViewModel
class FiltersViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]

    /** Where the editor's playhead was; the preview and thumbnails show this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private var session: ProjectSession? = null
    private val stills = StillRenderer(context)
    private var thumbnailJob: Job? = null
    private val mutableState = MutableStateFlow(FiltersUiState())

    val state: StateFlow<FiltersUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            opened.state.collect { session ->
                mutableState.update {
                    it.copy(
                        project = session.project,
                        clip = clipId?.let { id -> session.project.findClip(id)?.second },
                        loaded = true,
                    )
                }
            }
        }
    }

    fun category(category: FilterCategory) = mutableState.update { it.copy(category = category) }

    /** Picks filter [type] (null for None), keeping the current strength or starting at 80%. */
    fun pick(type: String?) {
        val clip = state.value.clip ?: return
        val strength = clip.filter?.strength ?: Filters.DEFAULT_STRENGTH
        session?.edit(SetFilter(clip.id, type, strength))
    }

    fun setStrength(strength: Float) {
        val clip = state.value.clip ?: return
        val filter = clip.filter ?: return
        session?.edit(SetFilter(clip.id, filter.type, strength))
    }

    /** Gives every video and photo this clip's filter. */
    fun applyToAll() {
        val clip = state.value.clip ?: return
        val filter = clip.filter
        session?.edit(ApplyFilterToAll(filter?.type, filter?.strength ?: 0f))
    }

    /**
     * Renders the thumbnails, [width] × [height] pixels each: the clip's picture at the playhead
     * through every filter, at full strength. All 25 (None first) are drawn in one frame, five
     * by five, and cut apart.
     */
    fun loadThumbnails(width: Int, height: Int) {
        if (thumbnailJob != null) return
        val project = state.value.project ?: return
        val clip = state.value.clip ?: return
        val source = clip.source ?: return
        val asset = project.asset(source.mediaId) ?: return
        thumbnailJob = viewModelScope.launch {
            val sourceTimeUs = source.inPointUs +
                ((playheadUs - clip.startUs).coerceAtLeast(0) * clip.speed.rate).toLong()
            val still = withContext(Dispatchers.IO) {
                Thumbnails.still(context, asset, mediaCache, sourceTimeUs, maxOf(width, height) * 2)
                    ?.let { centreCrop(it, width, height) }
            } ?: return@launch
            val types = listOf("") + Filters.all
            val grid = stills.render(gridTimeline(still, types, width, height), 0)
            val thumbnails = types.withIndex().associate { (index, type) ->
                type to gridCell(grid, index, width, height)
            }
            mutableState.update { it.copy(thumbnails = thumbnails) }
        }
    }

    override fun onCleared() {
        stills.release()
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    /** One cell per filter, each the still at a fifth of the grid, with its filter. */
    private fun gridTimeline(still: Bitmap, types: List<String>, width: Int, height: Int) =
        RenderTimeline(
            width = width * THUMBNAIL_GRID,
            height = height * THUMBNAIL_GRID,
            frameRate = 30,
            durationUs = 1_000_000,
            background = 0xFF000000.toInt(),
            layers = listOf(
                RenderLayer(
                    types.mapIndexed { index, type ->
                        RenderClip(
                            id = "tile$index",
                            startUs = 0,
                            durationUs = 1_000_000,
                            source = RenderSource.Image(still),
                            transform = Transform(
                                x = (index % THUMBNAIL_GRID + 0.5f) / THUMBNAIL_GRID - 0.5f,
                                y = (index / THUMBNAIL_GRID + 0.5f) / THUMBNAIL_GRID - 0.5f,
                                scale = 1f / THUMBNAIL_GRID,
                                fit = Fit.Fit,
                            ),
                            effects = if (type.isEmpty()) emptyList() else listOf(Effect(type, 1f)),
                        )
                    },
                ),
            ),
        )
}
