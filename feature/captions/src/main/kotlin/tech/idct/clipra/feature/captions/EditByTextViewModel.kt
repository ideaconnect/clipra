package tech.idct.clipra.feature.captions

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.CutChoices
import tech.idct.clipra.core.model.CutSummary
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextCuts
import tech.idct.clipra.core.model.Token
import tech.idct.clipra.core.model.TranscriptSource
import tech.idct.clipra.core.model.edit.CutTimeRanges
import tech.idct.clipra.core.model.edit.EditRejected
import javax.inject.Inject

/**
 * What Edit by text shows (#48): the transcribed sources, the cuts chosen so far and what they
 * add up to. Nothing changes in the project until Done; [edited] is the project as it will be.
 */
data class EditByTextUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val choices: CutChoices = CutChoices(),
    /** The preview's time in [edited]. */
    val playheadUs: Long = 0,
) {
    val sources: List<TranscriptSource> = project?.let(TextCuts::sources).orEmpty()
    val summary: CutSummary = TextCuts.summary(sources, choices)
    val ranges: List<LongRange> = TextCuts.ranges(sources, choices)

    /** The project with the cuts made, for the preview. */
    val edited: Project? = project?.let {
        try {
            CutTimeRanges(ranges).apply(it)
        } catch (_: EditRejected) {
            it
        }
    }

    /** The preview's time on the original timeline, to mark the word being spoken. */
    val originalPlayheadUs: Long
        get() {
            var time = playheadUs
            for (range in ranges) {
                if (range.first <= time) time += range.last - range.first else break
            }
            return time
        }
}

@HiltViewModel
class EditByTextViewModel @Inject constructor(
    private val projects: OpenProjects,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(EditByTextUiState())

    val state: StateFlow<EditByTextUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            opened.state.collect { session ->
                mutableState.update { it.copy(project = session.project, loaded = true) }
            }
        }
    }

    fun setFillers(on: Boolean) = choose { it.copy(fillers = on) }

    fun setPauses(on: Boolean) = choose { it.copy(pauses = on) }

    fun setThreshold(us: Long) = choose {
        it.copy(thresholdUs = us.coerceIn(CutChoices.MIN_THRESHOLD_US, CutChoices.MAX_THRESHOLD_US))
    }

    fun toggle(token: Token) = choose { it.toggle(token) }

    /** Restores every cut: nothing is cut until something is tapped or switched on. */
    fun restoreAll() = choose {
        CutChoices(fillers = false, pauses = false, thresholdUs = it.thresholdUs)
    }

    fun seek(timeUs: Long) = mutableState.update { it.copy(playheadUs = timeUs) }

    /** Makes the cuts, rippling through every layer, as one undo step. */
    fun apply() {
        val ranges = state.value.ranges
        if (ranges.isNotEmpty()) session?.edit(CutTimeRanges(ranges))
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private fun choose(change: (CutChoices) -> CutChoices) =
        mutableState.update { it.copy(choices = change(it.choices)) }
}
