package tech.idct.clipra.feature.effects.masks

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
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.SetEffectParam
import tech.idct.clipra.core.model.edit.SetMask
import tech.idct.clipra.core.model.filter
import javax.inject.Inject

/** What the Masks screen shows: the clip and its mask. */
data class MasksUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
) {
    /** The effect the mask limits: the one it names, else the clip's filter, else Mosaic. */
    val effectType: String
        get() = clip?.mask?.effect ?: clip?.filter?.type ?: DEFAULT_EFFECT

    companion object {
        /** A mask on a clip without effects starts by hiding a region in Mosaic. */
        const val DEFAULT_EFFECT = "mosaic"
    }
}

@HiltViewModel
class MasksViewModel @Inject constructor(
    private val projects: OpenProjects,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]

    /** Where the editor's playhead was; the preview shows this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(MasksUiState())

    val state: StateFlow<MasksUiState> = mutableState.asStateFlow()

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

    /** Picks the shape; None takes the mask away. The first shape adds the effect if needed. */
    fun shape(shape: MaskShape) {
        val clip = state.value.clip ?: return
        if (shape == MaskShape.None) {
            session?.edit(SetMask(clip.id, null))
            return
        }
        val mask = (clip.mask ?: Mask(feather = DEFAULT_FEATHER)).copy(shape = shape)
        val type = state.value.effectType
        session?.edit(SetMask(clip.id, mask.copy(effect = type), addIfMissing = type))
    }

    /** Commits a change to the mask (a drag, a slider, Invert, Applies to). */
    fun setMask(mask: Mask) {
        val clip = state.value.clip ?: return
        session?.edit(SetMask(clip.id, mask, addIfMissing = mask.effect))
    }

    fun setBlockSize(size: Float) {
        val clip = state.value.clip ?: return
        if (clip.effects.none { it.type == "mosaic" }) return
        session?.edit(SetEffectParam(clip.id, "mosaic", Filters.MOSAIC_SIZE, size))
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private companion object {
        const val DEFAULT_FEATHER = 0.04f
    }
}
