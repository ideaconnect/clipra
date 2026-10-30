package tech.idct.clipra.feature.captions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
import tech.idct.clipra.core.model.edit.EditCaptionWord
import tech.idct.clipra.core.model.edit.EditCommand
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.ImportCaptions
import tech.idct.clipra.core.model.edit.MergeCaptions
import tech.idct.clipra.core.model.edit.ReplaceInCaptions
import tech.idct.clipra.core.model.edit.SetCaptionLook
import tech.idct.clipra.core.model.edit.SplitCaption
import tech.idct.clipra.core.model.edit.linkedPartners
import javax.inject.Inject

/** What the Captions screen shows (#45). */
data class CaptionsEditUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val playheadUs: Long = 0,
    val message: CaptionsMessage? = null,
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

    /** The first word to check, as its caption and index, in time order. */
    val firstToCheck: Pair<String, Int>?
        get() = captions.firstNotNullOfOrNull { clip ->
            val index = clip.caption!!.words.indexOfFirst { it.confidence < LOW_CONFIDENCE }
            if (index >= 0) clip.id to index else null
        }

    companion object {
        /** Below this, a word is underlined to check (docs/spikes/whisper.md). */
        const val LOW_CONFIDENCE = 0.5f
    }
}

/** A short note after saving, importing, or an edit that couldn't be made (#46). */
sealed interface CaptionsMessage {
    data object Saved : CaptionsMessage

    data class Imported(val count: Int) : CaptionsMessage

    data object Unreadable : CaptionsMessage

    data object NotHere : CaptionsMessage

    data object Locked : CaptionsMessage
}

@HiltViewModel
class CaptionsEditViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

    // Editing (#46).

    fun editWord(clipId: String, index: Int, text: String) =
        edit(EditCaptionWord(clipId, index, text))

    fun split(clipId: String, index: Int) = edit(SplitCaption(clipId, index))

    fun merge(clipId: String) = edit(MergeCaptions(clipId))

    fun replaceAll(find: String, replacement: String) = edit(ReplaceInCaptions(find, replacement))

    /** Writes the captions to [uri], a file the user picked. */
    fun saveSrt(uri: Uri) {
        val project = state.value.project ?: return
        viewModelScope.launch {
            val saved = runCatching { SrtFiles.write(context, uri, project) }.isSuccess
            say(if (saved) CaptionsMessage.Saved else CaptionsMessage.Unreadable)
        }
    }

    /** Puts the captions of the .srt file at [uri] in place of the project's. */
    fun importSrt(uri: Uri) {
        viewModelScope.launch {
            val cues = SrtFiles.read(context, uri)
            if (cues.isEmpty()) return@launch say(CaptionsMessage.Unreadable)
            if (edit(ImportCaptions(cues))) say(CaptionsMessage.Imported(cues.size))
        }
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    private fun say(message: CaptionsMessage) = mutableState.update { it.copy(message = message) }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    /** Makes [command]; says why when it can't be made. */
    private fun edit(command: EditCommand): Boolean {
        val refused = session?.edit(command) ?: return true
        say(
            if (refused.reason == EditRejected.Reason.LayerLocked) {
                CaptionsMessage.Locked
            } else {
                CaptionsMessage.NotHere
            },
        )
        return false
    }
}
