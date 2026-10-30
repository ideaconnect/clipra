package tech.idct.clipra.feature.editor

import android.content.Context
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaImporter
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.Recording
import tech.idct.clipra.core.engine.media.SoundAnalyzer
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.engine.media.VoiceRecorder
import tech.idct.clipra.core.model.Animation
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Easing
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextFonts
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.Transform
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.model.edit.AddLayer
import tech.idct.clipra.core.model.edit.AddSound
import tech.idct.clipra.core.model.edit.AddText
import tech.idct.clipra.core.model.edit.AddToMosaic
import tech.idct.clipra.core.model.edit.DeleteClip
import tech.idct.clipra.core.model.edit.DuplicateClip
import tech.idct.clipra.core.model.edit.Edge
import tech.idct.clipra.core.model.edit.EditCommand
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.ExtractAudio
import tech.idct.clipra.core.model.edit.FreezeFrame
import tech.idct.clipra.core.model.edit.KeyframeTarget
import tech.idct.clipra.core.model.edit.LayerTarget
import tech.idct.clipra.core.model.edit.MoveClip
import tech.idct.clipra.core.model.edit.MoveKeyframe
import tech.idct.clipra.core.model.edit.MoveLayer
import tech.idct.clipra.core.model.edit.ReplaceMedia
import tech.idct.clipra.core.model.edit.SetAnimation
import tech.idct.clipra.core.model.edit.SetBlend
import tech.idct.clipra.core.model.edit.SetCell
import tech.idct.clipra.core.model.edit.SetKeyframeEasing
import tech.idct.clipra.core.model.edit.SetLayerHidden
import tech.idct.clipra.core.model.edit.SetLayerLocked
import tech.idct.clipra.core.model.edit.SetLayerMuted
import tech.idct.clipra.core.model.edit.SetSpeed
import tech.idct.clipra.core.model.edit.SetTransform
import tech.idct.clipra.core.model.edit.SplitClip
import tech.idct.clipra.core.model.edit.ToggleKeyframe
import tech.idct.clipra.core.model.edit.TrimClip
import tech.idct.clipra.core.model.edit.randomId
import tech.idct.clipra.core.model.timelineAt
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
    /** Beat markers (#40): each clip's beats on the timeline, for clips that show them. */
    val beats: Map<String, List<Long>> = emptyMap(),
    /** The voiceover panel (#41), while it is open. */
    val voiceover: VoiceoverState? = null,
)

/**
 * The voiceover panel (#41): ready, recording (for how long, with the meter's last levels in
 * dBFS, oldest first), or placing what was recorded.
 */
data class VoiceoverState(
    val recording: Boolean = false,
    val elapsedUs: Long = 0,
    val levels: List<Float> = emptyList(),
    val saving: Boolean = false,
)

/** A short note for the user after an edit was refused or media couldn't be read. */
enum class EditorMessage {
    LayerLocked,
    NoRoom,
    CantSplitHere,
    NotAvailable,
    ImportFailed,
    NeedsPicture,
    NeedsCut,
    NeedsSound,
    NeedsMicrophone,
    MicrophoneUnavailable,
}

@HiltViewModel
class EditorViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    private val importer: MediaImporter,
    val mediaCache: MediaCache,
    private val voices: VoiceCleaner,
    private val analyzer: SoundAnalyzer,
    private val recorder: VoiceRecorder,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState.get<String>("projectId")
    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(EditorUiState())

    /** The voiceover being recorded (#41), where it started, and its meter. */
    private var recording: Recording? = null
    private var recordingStartUs = 0L
    private var meter: Job? = null

    val state: StateFlow<EditorUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            // Beat markers (#40) follow the project and each finished analysis.
            launch {
                combine(opened.state.map { it.project }, mediaCache.voiceVersion) { p, _ -> p }
                    .collectLatest { loadBeats(it) }
            }
            opened.state.collect { session ->
                // Cleaned voices (#39) the cache lost, or that were asked for elsewhere.
                appScope.launch(Dispatchers.IO) { voices.requestAll(session.project) }
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

    /**
     * The beats (#40) of every clip with beat markers on, on the timeline: from the cache, found
     * now where they are missing (once per media).
     */
    private suspend fun loadBeats(project: Project) {
        val shown = project.layers.flatMap {
            it.clips
        }.filter { it.audio.beats && it.source != null }
        val beats = withContext(Dispatchers.IO) {
            shown.associate { clip ->
                val media = clip.source!!
                val asset = project.asset(media.mediaId)
                val found = asset?.let { analyzer.beats(it) ?: analyzer.prepareBeats(it) }
                clip.id to found?.timesUs.orEmpty()
                    .filter { it in media.inPointUs..media.outPointUs }
                    .mapNotNull { clip.timelineAt(it) }
                    .filter { it in clip.startUs..clip.endUs }
            }
        }
        mutableState.update { it.copy(beats = beats) }
    }

    /** Shows [message], such as why a tool has nothing to work on yet. */
    fun say(message: EditorMessage) = mutableState.update { it.copy(message = message) }

    fun undo() = session?.undo()

    fun redo() = session?.redo()

    // Clip tools, on the selected clip.

    fun split() = onSelected { SplitClip(it, state.value.playheadUs) }

    fun delete() = onSelected { DeleteClip(it, ripple = true) }

    fun duplicate() = onSelected { DuplicateClip(it) }

    fun freeze() = onSelected { FreezeFrame(it, state.value.playheadUs) }

    fun setSpeed(clipId: String, speed: Speed) = edit(SetSpeed(clipId, speed))

    fun setAnimation(clipId: String, animation: Animation) = edit(SetAnimation(clipId, animation))

    fun reverse() {
        val clipId = state.value.selectedClipId ?: return
        val clip = state.value.project?.findClip(clipId)?.second ?: return
        edit(SetSpeed(clipId, clip.speed.copy(reverse = !clip.speed.reverse)))
    }

    fun extractAudio() = onSelected { ExtractAudio(it) }

    fun move(clipId: String, startUs: Long, target: LayerTarget) =
        edit(MoveClip(clipId, startUs, target))

    fun trim(clipId: String, edge: Edge, toUs: Long) = edit(TrimClip(clipId, edge, toUs))

    // Voiceover (#41).

    fun openVoiceover() =
        mutableState.update { it.copy(voiceover = VoiceoverState(), selectedClipId = null) }

    fun closeVoiceover() {
        if (recording == null) mutableState.update { it.copy(voiceover = null) }
    }

    /** Starts recording at the playhead. The editor holds RECORD_AUDIO by now. */
    fun startRecording() {
        if (recording != null || state.value.voiceover?.saving == true) return
        val started = try {
            recorder.start()
        } catch (_: Exception) {
            return say(EditorMessage.MicrophoneUnavailable)
        }
        recording = started
        recordingStartUs = state.value.playheadUs
        mutableState.update { it.copy(voiceover = VoiceoverState(recording = true)) }
        meter = viewModelScope.launch {
            while (isActive) {
                delay(METER_INTERVAL_MS)
                val level = started.peakDb()
                mutableState.update { state ->
                    val panel = state.voiceover ?: return@update state
                    state.copy(
                        voiceover = panel.copy(
                            elapsedUs = started.elapsedUs,
                            levels = (panel.levels + level).takeLast(METER_LEVELS),
                        ),
                    )
                }
            }
        }
    }

    /**
     * Stops and places the voiceover where recording started, on the first free layer above the
     * main one, with AI voice cleanup on: it runs in the background while editing goes on.
     */
    fun stopRecording() {
        val active = recording ?: return
        recording = null
        meter?.cancel()
        val file = active.stop()
        if (file == null) {
            mutableState.update { it.copy(voiceover = VoiceoverState()) }
            return
        }
        mutableState.update { it.copy(voiceover = VoiceoverState(saving = true)) }
        val startUs = recordingStartUs
        viewModelScope.launch {
            val name = voiceoverName(state.value.project)
            val asset = try {
                importer.importRecording(file, name)
            } catch (_: Exception) {
                file.delete()
                mutableState.update {
                    it.copy(voiceover = null, message = EditorMessage.ImportFailed)
                }
                return@launch
            }
            val clip = voiceoverClip(asset, startUs)
            edit { project -> AddSound(clip).apply(project.withAsset(asset)) }
            mutableState.update { state ->
                val placed = state.project?.findClip(clip.id) != null
                state.copy(voiceover = null, selectedClipId = clip.id.takeIf { placed })
            }
        }
    }

    /** Throws the recording away, or closes the panel. */
    fun discardRecording() {
        meter?.cancel()
        recording?.cancel()
        recording = null
        mutableState.update { it.copy(voiceover = null) }
    }

    private fun voiceoverClip(asset: MediaAsset, startUs: Long): Clip {
        val engine = if (voices.isAvailable(VoiceEngine.DeepFilterNet)) {
            VoiceEngine.DeepFilterNet
        } else {
            VoiceEngine.RNNoise
        }
        val length = asset.durationUs.coerceAtLeast(MIN_VOICEOVER_US)
        return Clip(
            id = randomId(),
            type = ClipType.Audio,
            startUs = startUs,
            durationUs = length,
            source = MediaRef(asset.id, 0, length),
            audio = AudioSettings(voiceCleanup = VoiceCleanup(engine)),
        )
    }

    /** "voiceover.m4a", then "voiceover 2.m4a" and on, in the project. */
    private fun voiceoverName(project: Project?): String {
        val number = (project?.media?.count { it.recorded } ?: 0) + 1
        return if (number == 1) {
            context.getString(R.string.editor_voiceover_file)
        } else {
            context.getString(R.string.editor_voiceover_file_n, number)
        }
    }

    // Layers board (#19, #20).

    /** At the playhead: an animated clip gets the change in its keyframe there (#21). */
    fun setTransform(clipId: String, transform: Transform) =
        edit(SetTransform(clipId, transform, atUs = state.value.playheadUs))

    fun toggleKeyframe(clipId: String) =
        edit(ToggleKeyframe(clipId, state.value.playheadUs, KeyframeTarget.Transform))

    fun moveKeyframe(clipId: String, fromUs: Long, toUs: Long) =
        edit(MoveKeyframe(clipId, fromUs, toUs))

    fun setKeyframeEasing(clipId: String, atUs: Long, easing: Easing) =
        edit(SetKeyframeEasing(clipId, atUs, easing))

    fun setCell(clipId: String, cell: Int?) = edit(SetCell(clipId, cell))

    fun setBlend(clipId: String, blend: BlendMode, opacity: Float) =
        edit(SetBlend(clipId, blend, opacity, atUs = state.value.playheadUs))

    /**
     * Adds text saying [words] at the playhead, for three seconds, over the video; returns its
     * clip id, or null when it couldn't be added.
     */
    fun addText(words: String): String? {
        val project = state.value.project ?: return null
        val start = state.value.playheadUs.coerceIn(0, maxOf(0, project.durationUs))
        val clip = Clip(
            id = randomId(),
            type = ClipType.Text,
            startUs = start,
            durationUs = TEXT_DURATION_US,
            text = TextSpec(
                text = words,
                font = TextFonts.BRICOLAGE,
                weight = 800,
                size = 0.08f,
                color = TextColors.WHITE,
            ),
        )
        val session = session ?: return null
        val refused = session.edit(AddText(clip)) ?: return clip.id
        mutableState.update { it.copy(message = refused.toMessage()) }
        return null
    }

    /** Selects the clip [layerId] shows at the playhead, if any. */
    fun selectOnLayer(layerId: String) {
        val project = state.value.project ?: return
        val layer = project.layers.firstOrNull { it.id == layerId } ?: return
        val clip = layer.clips.firstOrNull { it.contains(state.value.playheadUs) } ?: return
        select(clip.id)
    }

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
        // Lets go of the microphone; the editor stops a recording itself (back, background).
        recording?.cancel()
        // The session saves in the app's scope, so the last edits are kept after the editor goes.
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
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
            return AddSound(clip).apply(withAsset)
        }
        // After the main layer's clips, or into the next empty mosaic cell (#17).
        val clip = Clip(randomId(), type, 0, duration, MediaRef(asset.id, 0, outPoint))
        return AddToMosaic(clip, randomId()).apply(withAsset)
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
        const val TEXT_DURATION_US = 3_000_000L
        const val METER_INTERVAL_MS = 50L

        /** About two seconds of levels on the meter. */
        const val METER_LEVELS = 40

        /** A voiceover is never shorter than a clip can be. */
        const val MIN_VOICEOVER_US = 100_000L
    }
}
