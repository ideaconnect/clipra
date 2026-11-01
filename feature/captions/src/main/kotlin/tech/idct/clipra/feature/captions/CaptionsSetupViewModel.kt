package tech.idct.clipra.feature.captions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.data.settings.SettingsRepository
import tech.idct.clipra.core.engine.media.Transcriber
import tech.idct.clipra.core.model.CaptionOptions
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.ImportCaptions
import tech.idct.clipra.core.model.edit.linkedPartners
import tech.idct.clipra.core.model.edit.originalCaptions
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelGroup
import tech.idct.clipra.core.models.ModelManager
import tech.idct.clipra.core.models.ModelState
import tech.idct.clipra.core.models.ModelStatus
import javax.inject.Inject

/** Which clips Auto captions listens to (CaptionsSetup board). */
enum class ListenTo { MainClips, Voiceover, AllTracks }

/** What the Auto captions setup shows (#44). */
data class CaptionsSetupUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val listenTo: ListenTo = ListenTo.MainClips,
    /** An ISO 639-1 code, or [Transcriber.AUTO]. */
    val language: String = Transcriber.AUTO,
    val translate: Boolean = false,
    val model: AiModel = ModelCatalog.whisperBase,
    /** The Whisper models with where each stands on this phone. */
    val models: List<ModelState> = emptyList(),
    val options: CaptionOptions = CaptionOptions(),
    val transcription: TranscriptionState = TranscriptionState.Idle,
    /** Generate was pressed before the model was downloaded. */
    val waitingForModel: Boolean = false,
    /** The .srt picked to import held no captions (#46). */
    val importFailed: Boolean = false,
    /** The clips with captions are heard again too, whatever [listenTo] says (#181). */
    val redoCaptioned: Boolean = false,
) {
    /** The clips that will be transcribed. */
    val sources: List<Clip>
        get() = project?.let { project ->
            val heard = sources(project, listenTo)
            if (redoCaptioned) (heard + captioned(project)).distinctBy { it.id } else heard
        }.orEmpty()

    /** The captions there are from [sources], which generating replaces (#181). */
    val replacing: Int
        get() = project?.let { project ->
            val ids = sources.map { it.id }.toSet()
            project.originalCaptions().count { caption ->
                project.linkedPartners(caption.id).any { it in ids }
            }
        } ?: 0

    val modelStatus: ModelStatus
        get() = models.firstOrNull { it.model == model }?.status ?: ModelStatus.Missing()
}

/**
 * The clips [listenTo] picks in [project]: the video clips with sound on the lowest layer that
 * has any (Main clips), sound clips (Voiceover), or every clip with sound (All tracks). Muted
 * layers are left out.
 */
fun sources(project: Project, listenTo: ListenTo): List<Clip> {
    val layers = project.layers.filterNot { it.muted }
    fun hasSound(clip: Clip) = clip.source?.let { project.asset(it.mediaId) }?.hasAudio == true
    val video = { clip: Clip -> clip.type == ClipType.Video && hasSound(clip) }
    val sound = { clip: Clip -> clip.type == ClipType.Audio && hasSound(clip) }
    return when (listenTo) {
        ListenTo.MainClips ->
            layers.firstOrNull { layer -> layer.clips.any(video) }?.clips?.filter(video).orEmpty()

        ListenTo.Voiceover -> layers.flatMap { it.clips }.filter(sound)

        ListenTo.AllTracks -> layers.flatMap { it.clips }.filter { video(it) || sound(it) }
    }
}

/** The clips in [project] that captions were made from. */
private fun captioned(project: Project): List<Clip> {
    val ids = project.originalCaptions().flatMap { project.linkedPartners(it.id) }.toSet()
    return project.layers.flatMap { it.clips }
        .filter { it.id in ids && (it.type == ClipType.Video || it.type == ClipType.Audio) }
}

@HiltViewModel
class CaptionsSetupViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    private val models: ModelManager,
    private val settings: SettingsRepository,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val mutableState = MutableStateFlow(CaptionsSetupUiState())
    private val doneEvents = Channel<Int>(Channel.CONFLATED)
    private var acquired = false

    /** Set once this screen started a transcription, so an old result doesn't count. */
    private var started = false
    private var session: ProjectSession? = null

    val state: StateFlow<CaptionsSetupUiState> = mutableState.asStateFlow()

    /** The number of words, once a transcription started here is done. */
    val done: Flow<Int> = doneEvents.receiveAsFlow()

    init {
        viewModelScope.launch {
            val saved = settings.captionModel.first()?.let(ModelCatalog::byId)
            if (saved != null) mutableState.update { it.copy(model = saved) }
        }
        viewModelScope.launch {
            models.models.collect { all ->
                val whisper = all.filter { it.model.group == ModelGroup.Captions }
                mutableState.update { it.copy(models = whisper) }
                val current = state.value
                if (current.waitingForModel && current.modelStatus is ModelStatus.Installed) {
                    mutableState.update { it.copy(waitingForModel = false) }
                    start()
                }
            }
        }
        projectId?.let { id -> watchProject(id) }
    }

    private fun watchProject(id: String) {
        viewModelScope.launch {
            val opened = projects.acquire(id)
            acquired = opened != null
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            launch {
                TranscriptionWorker.state(context, id).collect { transcription ->
                    mutableState.update { it.copy(transcription = transcription) }
                    if (started && transcription is TranscriptionState.Done) {
                        started = false
                        doneEvents.trySend(transcription.words)
                    }
                }
            }
            opened.state.collect { session ->
                mutableState.update {
                    it.copy(
                        project = session.project,
                        loaded = true,
                        options = if (it.project ==
                            null
                        ) {
                            session.project.captionOptions
                        } else {
                            it.options
                        },
                    )
                }
            }
        }
    }

    fun setListenTo(listenTo: ListenTo) = mutableState.update { it.copy(listenTo = listenTo) }

    /** Generate hears the clips with captions again too: the tablet's Transcribe tab (#181). */
    fun redoCaptioned() = mutableState.update { it.copy(redoCaptioned = true) }

    fun setLanguage(language: String) = mutableState.update { it.copy(language = language) }

    fun setTranslate(translate: Boolean) = mutableState.update { it.copy(translate = translate) }

    fun setOptions(options: CaptionOptions) = mutableState.update { it.copy(options = options) }

    fun selectModel(model: AiModel) {
        mutableState.update { it.copy(model = model, waitingForModel = false) }
        viewModelScope.launch { settings.setCaptionModel(model.id) }
    }

    /**
     * Puts the captions of the .srt file at [uri] in the project (#46), in place of any it has,
     * and finishes as a transcription would.
     */
    fun importSrt(uri: Uri) {
        viewModelScope.launch {
            val cues = SrtFiles.read(context, uri)
            val refused = if (cues.isEmpty()) null else session?.edit(ImportCaptions(cues))
            if (cues.isNotEmpty() && refused == null && session != null) {
                doneEvents.trySend(cues.size)
            } else {
                mutableState.update { it.copy(importFailed = true) }
            }
        }
    }

    /** Downloads [model] without generating. */
    fun download(model: AiModel) {
        viewModelScope.launch { models.download(model) }
    }

    /** Generates captions, downloading the model first when it is not on the phone. */
    fun generate() {
        val current = state.value
        if (current.sources.isEmpty()) return
        if (current.modelStatus !is ModelStatus.Installed) {
            mutableState.update { it.copy(waitingForModel = true) }
            viewModelScope.launch { models.download(current.model) }
            return
        }
        start()
    }

    fun cancel() {
        val id = projectId ?: return
        mutableState.update { it.copy(waitingForModel = false) }
        TranscriptionWorker.cancel(context, id)
        started = false
    }

    override fun onCleared() {
        val id = projectId ?: return
        if (acquired) appScope.launch { projects.release(id) }
    }

    private fun start() {
        val current = state.value
        val id = projectId ?: return
        started = true
        TranscriptionWorker.start(
            context,
            TranscriptionRequest(
                projectId = id,
                clipIds = current.sources.map { it.id },
                modelId = current.model.id,
                language = current.language,
                translate = current.translate,
                options = current.options,
            ),
        )
    }
}
