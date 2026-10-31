package tech.idct.clipra.feature.effects.reframe

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.Reframer
import tech.idct.clipra.core.engine.render.ProjectRender
import tech.idct.clipra.core.engine.render.StillRenderer
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipGeometry
import tech.idct.clipra.core.model.Point
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Reframe
import tech.idct.clipra.core.model.Size
import tech.idct.clipra.core.model.edit.randomId
import tech.idct.clipra.core.model.transformAt
import javax.inject.Inject
import kotlin.math.roundToInt

/** What the Auto-reframe board shows (#53). */
data class ReframeUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    /** The new formats offered, the project's own left out. */
    val ratios: List<AspectRatio> = emptyList(),
    val ratio: AspectRatio = AspectRatio.Portrait9x16,
    val follow: Reframe.Follow = Reframe.Follow.Subject,
    val motion: Int = Reframe.DEFAULT_MOTION,
    val keepInside: Boolean = true,
    /** The project's frame at the playhead. */
    val source: Bitmap? = null,
    /** Where the new frame would sit on [source], in fractions of it. */
    val window: Reframe.Box? = null,
    /** The face followed on [source], in Face mode. */
    val face: Reframe.Box? = null,
    /** The new frame at the playhead. */
    val result: Bitmap? = null,
    /** Making the copy: how far along, 0 to 1; null when not. */
    val creating: Float? = null,
    /** The copy, once it is made: the editor opens it. */
    val created: String? = null,
)

@HiltViewModel
class ReframeViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    private val repository: ProjectRepository,
    private val reframer: Reframer,
    private val cache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]

    /** Where the editor's playhead was; the previews show this moment. */
    private val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private val stills = StillRenderer(context)
    private val mutableState = MutableStateFlow(ReframeUiState())
    private var previewJob: Job? = null
    private var createJob: Job? = null
    private var acquired = false

    val state: StateFlow<ReframeUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val session = projectId?.let { projects.acquire(it) }
            acquired = session != null
            val project = session?.state?.value?.project
            if (project == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            val ratios = Reframe.ratios(project)
            mutableState.update {
                it.copy(
                    project = project,
                    loaded = true,
                    ratios = ratios,
                    ratio = ratios.first(),
                )
            }
            val source = frame(project, SOURCE_SIDE)
            mutableState.update { it.copy(source = source) }
            refresh()
        }
    }

    fun setRatio(ratio: AspectRatio) {
        mutableState.update { it.copy(ratio = ratio) }
        refresh()
    }

    fun setFollow(follow: Reframe.Follow) {
        mutableState.update { it.copy(follow = follow) }
        refresh()
    }

    /** Camera motion shapes the path, which the preview shows once the masks are made. */
    fun setMotion(motion: Int) {
        mutableState.update { it.copy(motion = motion.coerceIn(0, 100)) }
        refresh()
    }

    fun setKeepInside(keep: Boolean) {
        mutableState.update { it.copy(keepInside = keep) }
        refresh()
    }

    /**
     * Makes the copy named [name]: every shot tracked, its crop path made, titles kept inside if
     * asked; then the editor opens it. Leaving the board stops it.
     */
    fun create(name: String) {
        val start = state.value
        val project = start.project ?: return
        if (start.creating != null) return
        createJob = viewModelScope.launch {
            mutableState.update { it.copy(creating = 0f) }
            try {
                val samples = reframer.track(project, start.follow) { progress ->
                    mutableState.update { it.copy(creating = progress) }
                }
                val boxes = if (start.keepInside) {
                    withContext(Dispatchers.Default) { reframer.titleBoxes(project, start.ratio) }
                } else {
                    null
                }
                val copy = Reframe.apply(
                    project,
                    start.ratio,
                    start.follow,
                    samples,
                    start.motion,
                    boxes,
                ).copy(id = randomId(), name = name, createdAtMillis = System.currentTimeMillis())
                // Saved even if the board goes now, so the copy is never half made.
                appScope.launch { repository.save(copy) }.join()
                mutableState.update { it.copy(creating = null, created = copy.id) }
            } catch (cancelled: CancellationException) {
                mutableState.update { it.copy(creating = null) }
                throw cancelled
            }
        }
    }

    fun cancel() {
        createJob?.cancel()
    }

    /** The previews again, for the current choices, at the playhead. */
    private fun refresh() {
        val current = state.value
        val project = current.project ?: return
        val source = current.source ?: return
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            // A slider being dragged settles first.
            delay(SETTLE_MS)
            val oldFrame = project.format.outputSize
            val clip = followed(project)
            val picture = clip?.source?.let { project.asset(it.mediaId) }?.displaySize
            // The people along the clip as the copy will follow them, when their masks are made;
            // else what the frame at the playhead shows.
            val cached = if (current.follow == Reframe.Follow.Subject && clip != null) {
                withContext(Dispatchers.IO) { reframer.cachedPeople(project, clip) }
            } else {
                null
            }
            val sighting = if (cached == null) reframer.subjectIn(source, current.follow) else null
            val samples = when {
                clip == null -> emptyMap()

                cached != null -> mapOf(clip.id to cached)

                sighting != null && picture != null -> {
                    // From the frame into the followed clip's picture.
                    val point = ClipGeometry.toSource(
                        clip.transform.value,
                        oldFrame,
                        picture,
                        Point(sighting.at.x * oldFrame.width, sighting.at.y * oldFrame.height),
                    )
                    mapOf(clip.id to listOf(playheadUs - clip.startUs to point))
                }

                else -> emptyMap()
            }
            val boxes = if (current.keepInside) {
                withContext(Dispatchers.Default) { reframer.titleBoxes(project, current.ratio) }
            } else {
                null
            }
            val copy = withContext(Dispatchers.Default) {
                Reframe.apply(
                    project,
                    current.ratio,
                    current.follow,
                    samples,
                    current.motion,
                    boxes,
                )
            }
            val window = if (clip != null && picture != null) {
                window(clip, copy.findClip(clip.id)!!.second, project, copy, picture)
            } else {
                centred(project.format.ratio, current.ratio)
            }
            mutableState.update {
                it.copy(window = window, face = sighting?.face, result = null)
            }
            val result = frame(copy, RESULT_SIDE)
            mutableState.update { it.copy(result = result) }
        }
    }

    /** The clip the preview follows: the top one under the playhead that gets a crop path. */
    private fun followed(project: Project): Clip? = project.layers.asReversed()
        .flatMap { it.clips }
        .firstOrNull { Reframe.follows(it) && playheadUs >= it.startUs && playheadUs < it.endUs }

    /** Where the [moved] clip's new frame shows its picture at the playhead, on the old frame. */
    private fun window(
        clip: Clip,
        moved: Clip,
        project: Project,
        copy: Project,
        picture: Size,
    ): Reframe.Box {
        val oldFrame = project.format.outputSize
        val newFrame = copy.format.outputSize
        val corners = listOf(
            Point(0f, 0f),
            Point(newFrame.width.toFloat(), 0f),
            Point(0f, newFrame.height.toFloat()),
            Point(newFrame.width.toFloat(), newFrame.height.toFloat()),
        ).map { corner ->
            val at = ClipGeometry.toSource(
                moved.transformAt(playheadUs - moved.startUs),
                newFrame,
                picture,
                corner,
            )
            ClipGeometry.toFrame(clip.transform.value, oldFrame, picture, at.x, at.y)
        }
        return Reframe.Box(
            (corners.minOf { it.x } / oldFrame.width).coerceIn(0f, 1f),
            (corners.minOf { it.y } / oldFrame.height).coerceIn(0f, 1f),
            (corners.maxOf { it.x } / oldFrame.width).coerceIn(0f, 1f),
            (corners.maxOf { it.y } / oldFrame.height).coerceIn(0f, 1f),
        )
    }

    /** The largest window of [ratio] in the middle of a frame of [from]. */
    private fun centred(from: AspectRatio, ratio: AspectRatio): Reframe.Box {
        val old = from.width.toFloat() / from.height
        val new = ratio.width.toFloat() / ratio.height
        val width = if (new < old) new / old else 1f
        val height = if (new < old) 1f else old / new
        return Reframe.Box((1 - width) / 2, (1 - height) / 2, (1 + width) / 2, (1 + height) / 2)
    }

    /** [project]'s frame at the playhead from the proxies, its long side [side] px. */
    private suspend fun frame(project: Project, side: Int): Bitmap? = try {
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
        val at = playheadUs.coerceIn(0, maxOf(0, project.durationUs - 1))
        stills.render(timeline, at)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }

    override fun onCleared() {
        stills.release()
        if (acquired && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private companion object {
        const val SOURCE_SIDE = 720
        const val RESULT_SIDE = 480
        const val SETTLE_MS = 120L
    }
}
