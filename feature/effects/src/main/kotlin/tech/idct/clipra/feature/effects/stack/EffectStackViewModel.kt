package tech.idct.clipra.feature.effects.stack

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.AddEffect
import tech.idct.clipra.core.model.edit.EditCommand
import tech.idct.clipra.core.model.edit.MoveEffect
import tech.idct.clipra.core.model.edit.PasteEffects
import tech.idct.clipra.core.model.edit.RemoveEffect
import tech.idct.clipra.core.model.edit.SetEffectEnabled
import tech.idct.clipra.core.model.edit.SetEffectParam
import tech.idct.clipra.core.model.edit.SetEffectStrength
import javax.inject.Inject
import javax.inject.Singleton

/** Copied effects (#29), to paste on another clip; kept while the app runs. */
@Singleton
class EffectClipboard @Inject constructor() {
    val effects = MutableStateFlow<List<Effect>?>(null)
}

/** What the effect stack board shows: the clip, its stack and what was copied. */
data class EffectStackUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
    val copied: List<Effect>? = null,
) {
    val effects: List<Effect> get() = clip?.effects.orEmpty()

    /** The effects the clip doesn't have yet, which Add offers. */
    val addable: List<String> get() = Filters.all.filter { type ->
        effects.none { it.type == type }
    }
}

@HiltViewModel
class EffectStackViewModel @Inject constructor(
    private val projects: OpenProjects,
    private val clipboard: EffectClipboard,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]

    /** The clip whose stack this is; the tablet editor's panel (#70) follows its selection. */
    private val clipId = MutableStateFlow<String?>(savedState["clipId"])

    /** Where the editor's playhead was; the preview shows this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(EffectStackUiState())

    val state: StateFlow<EffectStackUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.value = mutableState.value.copy(loaded = true)
                return@launch
            }
            combine(opened.state, clipboard.effects, clipId) { session, copied, id ->
                EffectStackUiState(
                    project = session.project,
                    clip = id?.let { session.project.findClip(it)?.second },
                    loaded = true,
                    copied = copied,
                )
            }.collect { mutableState.value = it }
        }
    }

    /** The stack of [id] from now on (the tablet editor's selection, #70). */
    fun select(id: String?) {
        clipId.value = id
    }

    fun add(type: String) = edit { AddEffect(it, type) }

    fun move(from: Int, to: Int) = edit { MoveEffect(it, from, to) }

    fun setEnabled(index: Int, enabled: Boolean) = edit { SetEffectEnabled(it, index, enabled) }

    fun setStrength(index: Int, strength: Float) = edit { SetEffectStrength(it, index, strength) }

    fun setBlockSize(size: Float) = edit { SetEffectParam(it, "mosaic", Filters.MOSAIC_SIZE, size) }

    fun remove(index: Int) = edit { RemoveEffect(it, index) }

    fun copy() {
        clipboard.effects.value = state.value.effects
    }

    fun paste() {
        val copied = state.value.copied ?: return
        edit { PasteEffects(it, copied) }
    }

    private fun edit(command: (String) -> EditCommand) {
        val clip = state.value.clip ?: return
        session?.edit(command(clip.id))
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }
}
