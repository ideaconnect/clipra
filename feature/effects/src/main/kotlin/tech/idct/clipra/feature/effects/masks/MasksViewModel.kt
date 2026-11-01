package tech.idct.clipra.feature.effects.masks

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.FaceTracks
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.FaceBox
import tech.idct.clipra.core.model.FaceTracking
import tech.idct.clipra.core.model.Filters
import tech.idct.clipra.core.model.Mask
import tech.idct.clipra.core.model.MaskShape
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.FollowFace
import tech.idct.clipra.core.model.edit.KeyframeTarget
import tech.idct.clipra.core.model.edit.SetEffectParam
import tech.idct.clipra.core.model.edit.SetMask
import tech.idct.clipra.core.model.edit.ToggleKeyframe
import tech.idct.clipra.core.model.filter
import tech.idct.clipra.core.model.maskAt
import javax.inject.Inject

/** What the Masks screen shows: the clip and its mask. */
data class MasksUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
    /** Where the editor's playhead was: the mask shown and edited is the one at this moment. */
    val playheadUs: Long = 0,
    /** Follow face (#52) at work: how far along, 0 to 1; null when it isn't. */
    val tracking: Float? = null,
    /** The last Follow face found no face in the clip. */
    val noFace: Boolean = false,
) {
    /** The mask at the playhead, its keyframes applied (#21). */
    val mask: Mask?
        get() = clip?.let { it.maskAt(playheadUs - it.startUs) }

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
    private val faces: FaceTracks,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]

    /** Where the editor's playhead was; the preview shows this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(MasksUiState(playheadUs = playheadUs))
    private var tracking: Job? = null

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
        val mask = (state.value.mask ?: Mask(feather = DEFAULT_FEATHER)).copy(shape = shape)
        val type = state.value.effectType
        session?.edit(
            SetMask(clip.id, mask.copy(effect = type), addIfMissing = type, atUs = playheadUs),
        )
    }

    /** Commits a change to the mask (a drag, a slider, Invert, Applies to). */
    fun setMask(mask: Mask) {
        val clip = state.value.clip ?: return
        session?.edit(SetMask(clip.id, mask, addIfMissing = mask.effect, atUs = playheadUs))
    }

    /** Adds a mask keyframe at the playhead, or deletes the one there (#21). */
    fun toggleKeyframe() {
        val clip = state.value.clip ?: return
        session?.edit(ToggleKeyframe(clip.id, playheadUs, KeyframeTarget.Mask))
    }

    /**
     * Follow face (#52): finds the face along the clip (with progress) and makes the mask follow
     * it as keyframes, in one step; off stops following, leaving the mask where it is.
     */
    fun followFace(on: Boolean) {
        val clip = state.value.clip ?: return
        if (!on) {
            tracking?.cancel()
            tracking = null
            mutableState.update { it.copy(tracking = null) }
            if (clip.mask?.followFace == true) {
                session?.edit(FollowFace(clip.id, null, atUs = playheadUs))
            }
            return
        }
        if (tracking != null) return
        val asset = clip.source?.let { state.value.project?.asset(it.mediaId) } ?: return
        val effect = state.value.effectType
        // The face under the mask, as it is at the playhead.
        val atUs = (playheadUs - clip.startUs).coerceIn(0, clip.durationUs)
        val near = clip.maskAt(atUs)?.takeIf { it.shape != MaskShape.None }?.let {
            FaceBox(it.x, it.y, it.width, it.height)
        }
        mutableState.update { it.copy(tracking = 0f, noFace = false) }
        tracking = viewModelScope.launch {
            val samples = withContext(Dispatchers.Default) {
                faces.find(
                    asset,
                    clip,
                    atUs,
                    near,
                    onProgress = { p -> mutableState.update { it.copy(tracking = p) } },
                    stopped = { !isActive },
                )
            }
            val path = samples?.let(FaceTracking::track)
            if (path != null) {
                session?.edit(FollowFace(clip.id, path, atUs = playheadUs, addIfMissing = effect))
            }
            mutableState.update { it.copy(tracking = null, noFace = path == null) }
            tracking = null
        }
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
