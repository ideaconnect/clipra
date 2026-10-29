package tech.idct.clipra.feature.effects.transitions

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
import tech.idct.clipra.core.engine.render.RenderTransition
import tech.idct.clipra.core.engine.render.StillRenderer
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Cut
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.Fit
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.Transition
import tech.idct.clipra.core.model.Transitions
import tech.idct.clipra.core.model.cutAfter
import tech.idct.clipra.core.model.edit.ApplyTransitionToAll
import tech.idct.clipra.core.model.edit.SetTransition
import tech.idct.clipra.core.model.transitionOn
import tech.idct.clipra.feature.effects.common.THUMBNAIL_GRID
import tech.idct.clipra.feature.effects.common.centreCrop
import tech.idct.clipra.feature.effects.common.gridCell
import javax.inject.Inject

/** Which transitions the grid shows. */
enum class TransitionCategory { All, Basic, Motion, Stylized }

/**
 * What the Transitions screen shows: the cut after the editor's clip and its transition, if
 * any. [thumbnails] are keyed by transition id, "" for None.
 */
data class TransitionsUiState(
    val project: Project? = null,
    val cut: Cut? = null,
    val transition: Transition? = null,
    val loaded: Boolean = false,
    val category: TransitionCategory = TransitionCategory.All,
    val thumbnails: Map<String, Bitmap> = emptyMap(),
)

@HiltViewModel
class TransitionsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]

    // The clip before the cut.
    private val clipId: String? = savedState["clipId"]

    private var session: ProjectSession? = null
    private val stills = StillRenderer(context)
    private var thumbnailJob: Job? = null
    private val mutableState = MutableStateFlow(TransitionsUiState())

    val state: StateFlow<TransitionsUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            opened.state.collect { session ->
                val cut = clipId?.let { session.project.cutAfter(it) }
                mutableState.update {
                    it.copy(
                        project = session.project,
                        cut = cut,
                        transition = cut?.let { c -> session.project.transitionOn(c) },
                        loaded = true,
                    )
                }
            }
        }
    }

    fun category(category: TransitionCategory) =
        mutableState.update { it.copy(category = category) }

    /** Puts transition [type] on the cut (null removes it), keeping its duration and easing. */
    fun pick(type: String?) {
        val cut = state.value.cut ?: return
        val current = state.value.transition
        session?.edit(
            SetTransition(
                cut.layerId,
                cut.timeUs,
                type,
                current?.durationUs ?: Transitions.DEFAULT_DURATION_US,
                current?.easing ?: Easing.EaseInOut,
            ),
        )
    }

    fun setDuration(durationUs: Long) = change { it.copy(durationUs = durationUs) }

    fun setEasing(easing: Easing) = change { it.copy(easing = easing) }

    /** Puts this cut's transition on every cut. */
    fun applyToAll() {
        val current = state.value.transition
        session?.edit(
            ApplyTransitionToAll(
                current?.type,
                current?.durationUs ?: Transitions.DEFAULT_DURATION_US,
                current?.easing ?: Easing.EaseInOut,
            ),
        )
    }

    /**
     * Renders the thumbnails, [width] × [height] pixels each: the outgoing clip's last picture
     * and the incoming clip's first, halfway through every transition. All 25 (None first) are
     * drawn in one frame, five by five, and cut apart.
     */
    fun loadThumbnails(width: Int, height: Int) {
        if (thumbnailJob != null) return
        val project = state.value.project ?: return
        val cut = state.value.cut ?: return
        thumbnailJob = viewModelScope.launch {
            val side = maxOf(width, height) * 2
            val (from, to) = withContext(Dispatchers.IO) {
                val frameUs = 1_000_000L / project.format.frameRate.fps
                still(project, cut.from, cut.from.durationUs - frameUs, side) to
                    still(project, cut.to, 0, side)
            }
            if (from == null || to == null) return@launch
            val types = listOf("") + Transitions.all
            val fromCell = centreCrop(from, width, height)
            val toCell = centreCrop(to, width, height)
            val grid = stills.render(
                gridTimeline(fromCell, toCell, types, width, height),
                1_000_000,
            )
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

    private fun change(edit: (Transition) -> Transition) {
        val current = state.value.transition ?: return
        val next = edit(current)
        session?.edit(
            SetTransition(next.layerId, next.cutTimeUs, next.type, next.durationUs, next.easing),
        )
    }

    /** [clip]'s picture [offsetUs] into it. */
    private fun still(project: Project, clip: Clip, offsetUs: Long, side: Int): Bitmap? {
        val source = clip.source ?: return null
        val asset = project.asset(source.mediaId) ?: return null
        val timeUs = source.inPointUs + (offsetUs.coerceAtLeast(0) * clip.speed.rate).toLong()
        return Thumbnails.still(context, asset, mediaCache, timeUs, side)
    }

    /**
     * One cell per transition: the two pictures as a cut from 0 to 2 s with the transition over
     * the middle second, kept to its cell. At 1 s every transition is halfway.
     */
    private fun gridTimeline(
        from: Bitmap,
        to: Bitmap,
        types: List<String>,
        width: Int,
        height: Int,
    ) = RenderTimeline(
        width = width * THUMBNAIL_GRID,
        height = height * THUMBNAIL_GRID,
        frameRate = 30,
        durationUs = 2_000_000,
        background = 0xFF000000.toInt(),
        layers = types.mapIndexed { index, type ->
            val column = index % THUMBNAIL_GRID
            val row = index / THUMBNAIL_GRID
            val transform = Transform(
                x = (column + 0.5f) / THUMBNAIL_GRID - 0.5f,
                y = (row + 0.5f) / THUMBNAIL_GRID - 0.5f,
                scale = 1f / THUMBNAIL_GRID,
                fit = Fit.Fit,
            )
            val outgoing = RenderClip(
                "from$index",
                0,
                if (type.isEmpty()) 2_000_000 else 1_000_000,
                RenderSource.Image(from),
                transform = transform,
            )
            if (type.isEmpty()) return@mapIndexed RenderLayer(listOf(outgoing))
            val incoming = RenderClip(
                "to$index",
                1_000_000,
                1_000_000,
                RenderSource.Image(to),
                transform = transform,
            )
            val cell = RenderTransition.Region(
                column.toFloat() / THUMBNAIL_GRID,
                row.toFloat() / THUMBNAIL_GRID,
                1f / THUMBNAIL_GRID,
                1f / THUMBNAIL_GRID,
            )
            RenderLayer(
                clips = listOf(outgoing, incoming),
                transitions = listOf(
                    RenderTransition(
                        outgoing.id,
                        incoming.id,
                        500_000,
                        1_000_000,
                        type,
                        Easing.Linear,
                        cell,
                    ),
                ),
            )
        },
    )
}
