package tech.idct.clipra.feature.effects.cutout

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.Cutouts
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.SetCutout
import javax.inject.Inject

/** Where finding the people in a clip stands (#50). */
sealed interface PersonSearch {
    /** The masks are being made: how far along, 0 to 1. */
    data class Finding(val progress: Float) : PersonSearch

    data object Found : PersonSearch

    data object NotFound : PersonSearch
}

/** What the Cutout board shows (#50): the clip, its cutout and the search for people. */
data class CutoutUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val loaded: Boolean = false,
    val search: PersonSearch = PersonSearch.Finding(0f),
) {
    val cutout: Cutout get() = clip?.cutout ?: Cutout()
}

@HiltViewModel
class CutoutViewModel @Inject constructor(
    private val projects: OpenProjects,
    private val cutouts: Cutouts,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]

    /** Where the editor's playhead was; the preview shows this moment. */
    val playheadUs: Long = savedState["playheadUs"] ?: 0L

    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(CutoutUiState())

    val state: StateFlow<CutoutUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            val clip = clipId?.let { opened?.state?.value?.project?.findClip(it)?.second }
            if (opened == null || clip == null) {
                mutableState.value = CutoutUiState(loaded = true)
                return@launch
            }
            // Opening the board cuts the people out; the masks are made in the background.
            if (clip.cutout == null) opened.edit(SetCutout(clip.id, Cutout()))
            assetOf(opened.state.value.project, clip)?.let(cutouts::request)
            combine(
                opened.state,
                cutouts.progress,
                mediaCache.voiceVersion,
            ) { session, progress, _ ->
                val project = session.project
                val current = clipId?.let { project.findClip(it)?.second }
                val asset = current?.let { assetOf(project, it) }
                val search = when {
                    asset == null -> PersonSearch.NotFound

                    else -> progress[asset.id]?.let { PersonSearch.Finding(it) }
                        ?: withContext(Dispatchers.IO) { cutouts.masks(asset) }?.let { masks ->
                            if (masks.found) PersonSearch.Found else PersonSearch.NotFound
                        }
                        ?: PersonSearch.Finding(0f)
                }
                CutoutUiState(project, current, loaded = true, search = search)
            }.collect { mutableState.value = it }
        }
    }

    /** Commits [cutout]: a slider let go, or what goes behind picked. */
    fun set(cutout: Cutout) {
        val clip = state.value.clip ?: return
        if (cutout != clip.cutout) session?.edit(SetCutout(clip.id, cutout))
    }

    /** Takes the cutout away, leaving the whole picture. */
    fun remove() {
        val clip = state.value.clip ?: return
        if (clip.cutout != null) session?.edit(SetCutout(clip.id, null))
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private fun assetOf(project: Project, clip: Clip): MediaAsset? =
        clip.source?.let { project.asset(it.mediaId) }
}
