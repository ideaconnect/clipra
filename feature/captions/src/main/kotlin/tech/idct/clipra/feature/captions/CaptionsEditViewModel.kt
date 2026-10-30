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
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.edit.SetCaptionLook
import tech.idct.clipra.core.model.edit.linkedPartners
import javax.inject.Inject

/** What the Captions screen shows (#45). */
data class CaptionsEditUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val playheadUs: Long = 0,
) {
    /** The project's caption clips, in time order. */
    val captions: List<Clip>
        get() = project?.layers?.flatMap { it.clips }
            ?.filter { it.type == ClipType.Caption && it.caption != null }
            ?.sortedBy { it.startUs }.orEmpty()

    /** The look the captions share (the first one's). */
    val look: CaptionSpec? get() = captions.firstOrNull()?.caption

    /** The transcript the captions came from, for the model and the language. */
    val transcript: Transcript?
        get() {
            val project = project ?: return null
            val first = captions.firstOrNull() ?: return project.transcripts.firstOrNull()
            val sources = project.linkedPartners(first.id)
            return project.transcripts.firstOrNull { it.clipId in sources }
                ?: project.transcripts.firstOrNull()
        }

    /** Words Whisper was unsure of, to check (CaptionsEdit board). */
    val wordsToCheck: Int
        get() = captions.sumOf { clip ->
            clip.caption!!.words.count { it.confidence < LOW_CONFIDENCE }
        }

    companion object {
        /** Below this, a word is underlined to check (docs/spikes/whisper.md). */
        const val LOW_CONFIDENCE = 0.5f
    }
}

@HiltViewModel
class CaptionsEditViewModel @Inject constructor(
    private val projects: OpenProjects,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(CaptionsEditUiState())

    val state: StateFlow<CaptionsEditUiState> = mutableState.asStateFlow()

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

    fun setStyle(style: CaptionStyle) = edit(SetCaptionLook(style = style))

    fun setPosition(position: CaptionPosition) = edit(SetCaptionLook(position = position))

    fun setScale(scale: Float) = edit(SetCaptionLook(scale = scale))

    fun seek(timeUs: Long) = mutableState.update { it.copy(playheadUs = timeUs) }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private fun edit(look: SetCaptionLook) {
        session?.edit(look)
    }
}
