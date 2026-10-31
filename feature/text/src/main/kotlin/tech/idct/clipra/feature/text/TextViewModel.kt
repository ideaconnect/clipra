package tech.idct.clipra.feature.text

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.data.settings.TextTemplateRepository
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TextTemplate
import tech.idct.clipra.core.model.TextTemplates
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.edit.AddText
import tech.idct.clipra.core.model.edit.ApplyTextTemplate
import tech.idct.clipra.core.model.edit.SetAnimation
import tech.idct.clipra.core.model.edit.SetText
import tech.idct.clipra.core.model.edit.randomId
import java.util.UUID
import javax.inject.Inject

/** What the Text screen shows: the text clip being edited. */
data class TextUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
)

@HiltViewModel
class TextViewModel @Inject constructor(
    private val projects: OpenProjects,
    private val templateStore: TextTemplateRepository,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]

    /** The text clip being edited; the tablet editor's panels (#70) follow its selection. */
    private val clipId = MutableStateFlow<String?>(savedState["clipId"])

    /** Where the editor's playhead was; the preview shows this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(TextUiState())

    val state: StateFlow<TextUiState> = mutableState.asStateFlow()

    /** The built-in templates (#34), then the ones the user saved, newest first. */
    val templates: StateFlow<List<TextTemplate>> = templateStore.saved
        .map { TextTemplates.builtIn + it }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(), TextTemplates.builtIn)

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            combine(opened.state, clipId, ::Pair).collect { (session, id) ->
                mutableState.update {
                    it.copy(
                        project = session.project,
                        clip = id?.let { session.project.findClip(it)?.second }
                            ?.takeIf { clip -> clip.text != null },
                        loaded = true,
                    )
                }
            }
        }
    }

    /** Edits [id] from now on, or nothing (the tablet editor's selection, #70). */
    fun select(id: String?) {
        clipId.value = id
    }

    /**
     * Adds [words] at [atUs] for three seconds, in [template]'s look (a card when it has a card
     * colour, otherwise over the video), on the first free layer above; returns its id, or null
     * when it couldn't be added.
     */
    fun addText(words: String, template: TextTemplate, atUs: Long): String? {
        val session = session ?: return null
        val spec = template.spec.copy(text = words)
        val card = spec.background != null
        val clip = Clip(
            id = randomId(),
            type = if (card) ClipType.TextCard else ClipType.Text,
            startUs = atUs.coerceAtLeast(0),
            durationUs = NEW_TEXT_US,
            text = spec,
            transform = Keyframed(Transform(y = if (card) 0f else template.y ?: 0f)),
        )
        if (session.edit(AddText(clip)) != null) return null
        select(clip.id)
        return clip.id
    }

    /** Commits a change to the words or the style. */
    fun setSpec(spec: TextSpec) {
        val clip = state.value.clip ?: return
        if (clip.text != spec) session?.edit(SetText(clip.id, spec))
    }

    /**
     * Commits [words] as the words of [clipId], whichever clip is edited now: what was typed into
     * a clip lands in it even when the selection has moved on (#70).
     */
    fun setWords(clipId: String, words: String) {
        val session = session ?: return
        val spec = session.state.value.project.findClip(clipId)?.second?.text ?: return
        if (spec.text != words) session.edit(SetText(clipId, spec.copy(text = words)))
    }

    /** Commits the text's animation in and out (#33). */
    fun setAnimation(animation: Animation) {
        val clip = state.value.clip ?: return
        if (clip.animation != animation) session?.edit(SetAnimation(clip.id, animation))
    }

    /** Gives the clip [template]'s look, keeping its words (#34). */
    fun applyTemplate(template: TextTemplate) {
        val clip = state.value.clip ?: return
        session?.edit(ApplyTextTemplate(clip.id, template))
    }

    /** Keeps the clip's look as a template for every project (#34). */
    fun saveTemplate() {
        // From the session, which already has the words typed just before.
        val clip = clipId.value?.let { session?.state?.value?.project?.findClip(it)?.second }
            ?: return
        val template = TextTemplates.of(clip, UUID.randomUUID().toString()) ?: return
        appScope.launch { templateStore.save(template) }
    }

    fun deleteTemplate(id: String) {
        appScope.launch { templateStore.delete(id) }
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private companion object {
        /** New text lasts three seconds, as the editor's own. */
        const val NEW_TEXT_US = 3_000_000L
    }
}
