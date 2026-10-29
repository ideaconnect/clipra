package tech.idct.clipra.feature.audio

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.engine.media.Waveforms
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.SetAudio
import javax.inject.Inject
import kotlin.math.abs

/**
 * What the Audio screen shows: the clip, its sound settings and its waveform, and the
 * loudness of its stretch of media ([measuring] until it is known; null when it has no sound).
 */
data class AudioUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val waveform: Waveform? = null,
    val loaded: Boolean = false,
    val measured: Loudness.Measurement? = null,
    val measuring: Boolean = true,
)

@HiltViewModel
class AudioViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    private val cache: MediaCache,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]
    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(AudioUiState())

    val state: StateFlow<AudioUiState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            val opened = projectId?.let { projects.acquire(it) }
            session = opened
            if (opened == null) {
                mutableState.update { it.copy(loaded = true) }
                return@launch
            }
            launch { loadWaveform(opened.project) }
            launch { measure(opened.project) }
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

    /** Commits the clip's volume and fades. */
    fun setAudio(audio: AudioSettings) {
        val clip = state.value.clip ?: return
        if (clip.audio != audio) session?.edit(SetAudio(clip.id, audio))
    }

    /** Normalizes the clip's loudness to [target] LUFS, or stops normalizing when null. */
    fun setLoudnessTarget(target: Int?) {
        val clip = state.value.clip ?: return
        val measured = state.value.measured
        if (target != null && measured == null) return
        setAudio(clip.audio.measured(measured).copy(loudnessTarget = target))
    }

    override fun onCleared() {
        if (session != null && projectId != null) appScope.launch { projects.release(projectId) }
    }

    private suspend fun loadWaveform(project: Project) {
        val clip = clipId?.let { project.findClip(it)?.second } ?: return
        val asset = clip.source?.let { project.asset(it.mediaId) } ?: return
        val media = ProjectMediaResolver(context, cache, forExport = false, maxImageSide = 0)
        val waveform = withContext(Dispatchers.IO) {
            runCatching { Waveforms.waveform(context, asset, cache, media) }.getOrNull()
        }
        mutableState.update { it.copy(waveform = waveform) }
    }

    /**
     * Measures the clip's stretch of its original media. A normalized clip that was trimmed
     * since it was last measured gets the new measurement, so the export stays on target.
     */
    private suspend fun measure(project: Project) {
        val clip = clipId?.let { project.findClip(it)?.second }
        val source = clip?.source
        val asset = source?.let { project.asset(it.mediaId) }
        val measured = if (asset == null || (clip.type == ClipType.Video && !asset.hasAudio)) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val media = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 0)
                val path = media.source(asset) ?: return@withContext null
                val endUs = source.inPointUs + (clip.durationUs * clip.speed.rate).toLong()
                runCatching { Loudness.measure(context, path, source.inPointUs, endUs) }.getOrNull()
            }
        }
        mutableState.update { it.copy(measured = measured, measuring = false) }
        val current = state.value.clip ?: return
        val stored = current.audio.measuredLufs
        if (measured != null && current.audio.loudnessTarget != null &&
            (stored == null || abs(stored - measured.lufs) > STALE_LU)
        ) {
            setAudio(current.audio.measured(measured))
        }
    }

    private fun AudioSettings.measured(measured: Loudness.Measurement?) = if (measured == null) {
        this
    } else {
        copy(measuredLufs = measured.lufs, truePeakDb = measured.truePeakDb)
    }

    private companion object {
        /** How far a stored measurement may drift before it is replaced, in LU. */
        const val STALE_LU = 0.2f
    }
}
