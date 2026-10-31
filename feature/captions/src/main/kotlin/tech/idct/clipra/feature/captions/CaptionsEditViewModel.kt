package tech.idct.clipra.feature.captions

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.CaptionTranslator
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
import tech.idct.clipra.core.model.edit.ShowCaptionLanguage
import tech.idct.clipra.core.model.edit.SplitCaption
import tech.idct.clipra.core.model.edit.TranslateCaptions
import tech.idct.clipra.core.model.edit.captionsIn
import tech.idct.clipra.core.model.edit.linkedPartners
import tech.idct.clipra.core.model.edit.originalCaptions
import tech.idct.clipra.core.model.edit.shownCaptionLanguage
import tech.idct.clipra.core.model.edit.spokenCaptionLanguage
import tech.idct.clipra.core.model.edit.translatedCaptionLanguages
import tech.idct.clipra.core.models.AiModel
import tech.idct.clipra.core.models.ModelManager
import tech.idct.clipra.core.models.ModelStatus
import tech.idct.clipra.core.models.ModelStore
import java.io.IOException
import javax.inject.Inject

/** What the Captions screen shows (#45). */
data class CaptionsEditUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val playheadUs: Long = 0,
    val message: CaptionsMessage? = null,
    /** The Translate offline sheet (#47), while it is open. */
    val translate: TranslateSheet? = null,
) {
    /** The language shown (#47): "" for the captions as heard, or a translation's. */
    val shownLanguage: String get() = project?.shownCaptionLanguage().orEmpty()

    /** The language the captions were heard in, when a transcript says. */
    val spokenLanguage: String? get() = project?.spokenCaptionLanguage()

    /** The languages the captions were translated into (#47). */
    val translations: List<String> get() = project?.translatedCaptionLanguages().orEmpty()

    /** The project's caption clips in the language shown, in time order. */
    val captions: List<Clip>
        get() = project?.captionsIn(shownLanguage)?.filter { it.caption != null }.orEmpty()

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

/**
 * The Translate offline sheet (#47): the captions' language [from] (the transcript's, or the
 * one picked from [sources] when none says), the languages it goes into, and what is going on.
 */
data class TranslateSheet(
    val from: String?,
    val targets: List<TranslateTarget>,
    val sources: List<String>,
    val busy: TranslateBusy? = null,
)

/** A language the captions can go into, and what its models still need to download. */
data class TranslateTarget(val language: String, val downloadBytes: Long)

/** A translation under way: its language and its step. */
data class TranslateBusy(val language: String, val step: Step) {
    enum class Step { Downloading, WaitingForWifi, Translating }
}

/** A short note after saving, importing, or an edit that couldn't be made (#46). */
sealed interface CaptionsMessage {
    data object Saved : CaptionsMessage

    /** The .srt file couldn't be written, as when the place picked is gone or read-only. */
    data object NotSaved : CaptionsMessage

    /** The captions are in [language] now (#47). */
    data class Translated(val language: String) : CaptionsMessage

    data object TranslationFailed : CaptionsMessage

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
    private val translator: CaptionTranslator,
    private val models: ModelManager,
    private val store: ModelStore,
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
            say(if (saved) CaptionsMessage.Saved else CaptionsMessage.NotSaved)
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

    // Translation (#47).

    /** Shows the captions in [language], "" for the captions as heard. */
    fun showLanguage(language: String) = edit(ShowCaptionLanguage(language))

    fun openTranslate() = mutableState.update { it.copy(translate = sheet(it.spokenLanguage)) }

    /** Picks the captions' language when no transcript says, as after an .srt import. */
    fun pickSource(language: String) = mutableState.update { current ->
        if (current.translate?.busy != null) current else current.copy(translate = sheet(language))
    }

    /** Closes the sheet, and stops the translation under way. */
    fun closeTranslate() {
        translating?.cancel()
        mutableState.update { it.copy(translate = null) }
    }

    /**
     * Translates the captions as heard into [to]: downloads the models the route still needs
     * (the AI models' rules: Wi-Fi only unless allowed), translates on the phone, and adds the
     * captions in [to] as one undo step, shown.
     */
    fun translate(to: String) {
        val sheet = state.value.translate ?: return
        val from = sheet.from ?: return
        val route = translator.route(from, to) ?: return
        if (sheet.busy != null) return
        translating = viewModelScope.launch {
            val missing = route.filterNot(store::isInstalled)
            if (missing.isNotEmpty() && !download(missing, to)) return@launch fail()
            busy(to, TranslateBusy.Step.Translating)
            val originals = state.value.project?.originalCaptions().orEmpty()
            val lines = try {
                translator.translate(originals.map { it.caption!!.text }, from, to)
            } catch (_: IOException) {
                return@launch fail()
            }
            val done = edit(TranslateCaptions(to, originals.map { it.id }.zip(lines).toMap()))
            mutableState.update { it.copy(translate = null) }
            if (done) say(CaptionsMessage.Translated(to))
        }
    }

    private var translating: Job? = null

    /** Downloads [missing] and waits until each is installed; false when one fails. */
    private suspend fun download(missing: List<AiModel>, to: String): Boolean {
        missing.forEach { models.download(it) }
        // A failure from an earlier attempt counts only once this one has started.
        var started = false
        val statuses = models.models
            .map { all -> missing.map { model -> all.first { it.model.id == model.id }.status } }
            .first { statuses ->
                if (statuses.any { it is ModelStatus.Downloading || it is ModelStatus.Waiting }) {
                    started = true
                }
                val waiting = statuses.any { it is ModelStatus.Waiting && it.wifiOnly }
                busy(
                    to,
                    if (waiting) {
                        TranslateBusy.Step.WaitingForWifi
                    } else {
                        TranslateBusy.Step.Downloading
                    },
                )
                statuses.all { it is ModelStatus.Installed } ||
                    (started && statuses.any { it is ModelStatus.Missing && it.failure != null })
            }
        return statuses.all { it is ModelStatus.Installed }
    }

    private fun busy(language: String, step: TranslateBusy.Step) = mutableState.update {
        it.copy(translate = it.translate?.copy(busy = TranslateBusy(language, step)))
    }

    private fun fail() {
        mutableState.update { it.copy(translate = it.translate?.copy(busy = null)) }
        say(CaptionsMessage.TranslationFailed)
    }

    /** The sheet for captions in [from]: where they can go, and what each still downloads. */
    private fun sheet(from: String?): TranslateSheet = TranslateSheet(
        from = from,
        targets = from?.let { source ->
            translator.targets(source).map { target ->
                val route = translator.route(source, target).orEmpty()
                TranslateTarget(
                    target,
                    route.filterNot(store::isInstalled).sumOf { it.downloadBytes },
                )
            }
        }.orEmpty(),
        sources = translator.languages,
    )

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
