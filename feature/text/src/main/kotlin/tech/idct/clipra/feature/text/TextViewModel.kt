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
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TextTemplate
import tech.idct.clipra.core.model.TextTemplates
import tech.idct.clipra.core.model.edit.ApplyTextTemplate
import tech.idct.clipra.core.model.edit.SetAnimation
import tech.idct.clipra.core.model.edit.SetText
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
    private val clipId: String? = savedState["clipId"]

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

    /** Commits a change to the words or the style. */
    fun setSpec(spec: TextSpec) {
        val clip = state.value.clip ?: return
        if (clip.text != spec) session?.edit(SetText(clip.id, spec))
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
        val clip = clipId?.let { session?.state?.value?.project?.findClip(it)?.second } ?: return
        val template = TextTemplates.of(clip, UUID.randomUUID().toString()) ?: return
        appScope.launch { templateStore.save(template) }
    }

    fun deleteTemplate(id: String) {
        appScope.launch { templateStore.delete(id) }
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }
}
