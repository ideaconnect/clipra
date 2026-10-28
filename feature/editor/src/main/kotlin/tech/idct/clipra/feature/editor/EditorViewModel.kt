package tech.idct.clipra.feature.editor

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
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaImporter
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.AddClip
import tech.idct.clipra.core.model.edit.AddLayer
import tech.idct.clipra.core.model.edit.DeleteClip
import tech.idct.clipra.core.model.edit.DuplicateClip
import tech.idct.clipra.core.model.edit.Edge
import tech.idct.clipra.core.model.edit.EditCommand
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.ExtractAudio
import tech.idct.clipra.core.model.edit.FreezeFrame
import tech.idct.clipra.core.model.edit.LayerTarget
import tech.idct.clipra.core.model.edit.MoveClip
import tech.idct.clipra.core.model.edit.MoveLayer
import tech.idct.clipra.core.model.edit.ReplaceMedia
import tech.idct.clipra.core.model.edit.SetLayerHidden
import tech.idct.clipra.core.model.edit.SetLayerLocked
import tech.idct.clipra.core.model.edit.SetLayerMuted
import tech.idct.clipra.core.model.edit.SplitClip
import tech.idct.clipra.core.model.edit.TrimClip
import tech.idct.clipra.core.model.edit.randomId
import tech.idct.clipra.feature.editor.timeline.TimelineGeometry
import javax.inject.Inject

/** What the editor shows. [project] is null while the project loads, or if it is gone. */
data class EditorUiState(
    val project: Project? = null,
    val loaded: Boolean = false,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val selectedClipId: String? = null,
    val playheadUs: Long = 0,
    val dpPerSecond: Float = TimelineGeometry.DEFAULT_DP_PER_SECOND,
    val missingMedia: Set<String> = emptySet(),
    val importing: Boolean = false,
    val message: EditorMessage? = null,
)

/** A short note for the user after an edit was refused or media couldn't be read. */
enum class EditorMessage { LayerLocked, NoRoom, CantSplitHere, NotAvailable, ImportFailed }

@HiltViewModel
class EditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: ProjectRepository,
    private val importer: MediaImporter,
    val mediaCache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState.get<String>("projectId")
    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(EditorUiState())

    val state: StateFlow<EditorUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.open(it, appScope) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            opened.state.collect { session ->
                mutableState.update { state ->
                    state.copy(
                        project = session.project,
                        loaded = true,
                        canUndo = session.canUndo,
                        canRedo = session.canRedo,
                        selectedClipId = state.selectedClipId?.takeIf {
                            session.project.findClip(it) !=
                                null
                        },
                        missingMedia = missing(session.project),
                    )
                }
            }
        }
    }

    fun select(clipId: String?) = mutableState.update { it.copy(selectedClipId = clipId) }

    fun seek(timeUs: Long) {
        val end = state.value.project?.durationUs ?: 0
        mutableState.update { it.copy(playheadUs = timeUs.coerceIn(0, end)) }
    }

    fun zoom(factor: Float) = mutableState.update {
        val dps = (it.dpPerSecond * factor)
            .coerceIn(TimelineGeometry.MIN_DP_PER_SECOND, TimelineGeometry.MAX_DP_PER_SECOND)
        it.copy(dpPerSecond = dps)
    }

    fun dismissMessage() = mutableState.update { it.copy(message = null) }

    fun undo() = session?.undo()

    fun redo() = session?.redo()

    // Clip tools, on the selected clip.

    fun split() = onSelected { SplitClip(it, state.value.playheadUs) }

    fun delete() = onSelected { DeleteClip(it, ripple = true) }

    fun duplicate() = onSelected { DuplicateClip(it) }

    fun freeze() = onSelected { FreezeFrame(it, state.value.playheadUs) }

    fun extractAudio() = onSelected { ExtractAudio(it) }

    fun move(clipId: String, startUs: Long, target: LayerTarget) =
        edit(MoveClip(clipId, startUs, target))

    fun trim(clipId: String, edge: Edge, toUs: Long) = edit(TrimClip(clipId, edge, toUs))

    // Layers.

    fun setHidden(layerId: String, hidden: Boolean) = edit(SetLayerHidden(layerId, hidden))

    fun setMuted(layerId: String, muted: Boolean) = edit(SetLayerMuted(layerId, muted))

    fun setLocked(layerId: String, locked: Boolean) = edit(SetLayerLocked(layerId, locked))

    fun addLayer() = edit(AddLayer(randomId()))

    fun moveLayer(layerId: String, toIndex: Int) = edit(MoveLayer(layerId, toIndex))

    /**
     * Imports picked media: videos and photos go to the end of the main layer (L1), sounds to the
     * first free layer above it at the playhead. Each import is one undo step.
     */
    fun import(uris: List<Uri>) {
        if (uris.isEmpty()) return
        mutableState.update { it.copy(importing = true) }
        viewModelScope.launch {
            for (uri in uris) {
                val asset = try {
                    importer.import(uri)
                } catch (_: Exception) {
                    mutableState.update { it.copy(message = EditorMessage.ImportFailed) }
                    continue
                }
                edit { project -> placeImported(project, asset) }
            }
            mutableState.update { it.copy(importing = false) }
        }
    }

    /** Replaces the selected clip's media with [uri]. */
    fun replace(uri: Uri) {
        val clipId = state.value.selectedClipId ?: return
        viewModelScope.launch {
            val asset = try {
                importer.import(uri)
            } catch (_: Exception) {
                mutableState.update { it.copy(message = EditorMessage.ImportFailed) }
                return@launch
            }
            edit { project -> ReplaceMedia(clipId, asset.id).apply(project.withAsset(asset)) }
        }
    }

    /** Saves at once, for example when the app goes to the background. */
    fun flush() {
        viewModelScope.launch { session?.flush() }
    }

    override fun onCleared() {
        // The session saves in the app's scope, so the last edits are kept after the editor goes.
        session?.let { open -> appScope.launch { open.close() } }
    }

    private fun onSelected(command: (String) -> EditCommand) {
        state.value.selectedClipId?.let { edit(command(it)) }
    }

    private fun edit(command: EditCommand) {
        val refused = session?.edit(command) ?: return
        mutableState.update { it.copy(message = refused.toMessage()) }
    }

    private fun placeImported(project: Project, asset: MediaAsset): Project {
        val withAsset = project.withAsset(asset)
        val duration = if (asset.kind == MediaKind.Photo) PHOTO_DURATION_US else asset.durationUs
        val type = when (asset.kind) {
            MediaKind.Video -> ClipType.Video
            MediaKind.Photo -> ClipType.Photo
            MediaKind.Audio -> ClipType.Audio
        }
        val outPoint = if (asset.kind == MediaKind.Photo) 0 else duration
        if (asset.kind == MediaKind.Audio) {
            val start = state.value.playheadUs
            val clip = Clip(randomId(), type, start, duration, MediaRef(asset.id, 0, outPoint))
            val free = withAsset.layers.drop(1).firstOrNull { layer ->
                !layer.locked &&
                    layer.clips.none { it.startUs < clip.endUs && clip.startUs < it.endUs }
            }
            val target =
                free?.let { LayerTarget.Existing(it.id) } ?: LayerTarget.NewOnTop(randomId())
            return AddClip(clip, target).apply(withAsset)
        }
        val main = withAsset.layers.first()
        val start = main.clips.maxOfOrNull { it.endUs } ?: 0L
        val clip = Clip(randomId(), type, start, duration, MediaRef(asset.id, 0, outPoint))
        return AddClip(clip, LayerTarget.Existing(main.id)).apply(withAsset)
    }

    private fun Project.withAsset(asset: MediaAsset): Project =
        if (media.any { it.id == asset.id }) this else copy(media = media + asset)

    private fun missing(project: Project): Set<String> = project.media
        .filterNot {
            ProjectMediaResolver.isAvailable(context, it.uri) ||
                mediaCache.proxy(it.id).exists()
        }
        .map { it.id }
        .toSet()

    private fun EditRejected.toMessage(): EditorMessage = when (reason) {
        EditRejected.Reason.LayerLocked -> EditorMessage.LayerLocked
        EditRejected.Reason.Overlap -> EditorMessage.NoRoom
        EditRejected.Reason.OutOfRange -> EditorMessage.CantSplitHere
        else -> EditorMessage.NotAvailable
    }

    private companion object {
        const val PHOTO_DURATION_US = 3_000_000L
    }
}
