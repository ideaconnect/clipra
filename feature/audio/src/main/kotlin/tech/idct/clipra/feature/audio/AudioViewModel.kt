package tech.idct.clipra.feature.audio

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.di.ApplicationScope
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectSession
import tech.idct.clipra.core.engine.media.Loudness
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.ProjectMediaResolver
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.engine.media.Waveform
import tech.idct.clipra.core.engine.media.Waveforms
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine
import tech.idct.clipra.core.model.edit.ApplyVoiceCleanupToAll
import tech.idct.clipra.core.model.edit.SetAudio
import tech.idct.clipra.core.models.ModelCatalog
import tech.idct.clipra.core.models.ModelManager
import tech.idct.clipra.core.models.ModelStatus
import javax.inject.Inject
import kotlin.math.abs

/**
 * What the Audio screen shows: the clip, its sound settings and its waveform, and the
 * loudness of what it plays ([measuring] until it is known; null when it has no sound).
 */
data class AudioUiState(
    val project: Project? = null,
    val clip: Clip? = null,
    val waveform: Waveform? = null,
    val loaded: Boolean = false,
    val measured: Loudness.Measurement? = null,
    val measuring: Boolean = true,
    val voice: VoiceUiState = VoiceUiState(),
    /** The recording's own loudness, for Before; [measured] when nothing cleans it up. */
    val recorded: Loudness.Measurement? = measured,
)

/**
 * AI voice cleanup (#39): where DeepFilterNet 3's download stands, the clip's cleanup progress
 * while it runs, and the cleaned sound's waveform once it is ready.
 */
data class VoiceUiState(
    val deepFilterNet: ModelStatus = ModelStatus.Missing(),
    val cleaning: Float? = null,
    val cleanedWaveform: Waveform? = null,
)

@HiltViewModel
class AudioViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val projects: OpenProjects,
    private val cache: MediaCache,
    private val voices: VoiceCleaner,
    private val models: ModelManager,
    @param:ApplicationScope private val appScope: CoroutineScope,
    savedState: SavedStateHandle,
) : ViewModel() {
    private val projectId: String? = savedState["projectId"]
    private val clipId: String? = savedState["clipId"]
    private var session: ProjectSession? = null
    private val mutableState = MutableStateFlow(AudioUiState())

    /** The engine chosen before its model was downloaded; it is used once the model is in. */
    private var pendingEngine: VoiceEngine? = null

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
            launch { watchVoice() }
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

    /** Turns AI voice cleanup on (DeepFilterNet 3 when downloaded, else RNNoise) or off. */
    fun setVoiceOn(on: Boolean) {
        if (!on) return setVoiceCleanup(null)
        val engine = if (voices.isAvailable(VoiceEngine.DeepFilterNet)) {
            VoiceEngine.DeepFilterNet
        } else {
            VoiceEngine.RNNoise
        }
        setVoiceCleanup(VoiceCleanup(engine))
    }

    /** Picks the engine; DeepFilterNet 3 is downloaded first and picked when it is in. */
    fun chooseEngine(engine: VoiceEngine) {
        if (!voices.isAvailable(engine)) {
            pendingEngine = engine
            viewModelScope.launch { models.download(ModelCatalog.deepFilterNet) }
            return
        }
        pendingEngine = null
        val current = state.value.clip?.audio?.voiceCleanup ?: VoiceCleanup()
        setVoiceCleanup(current.copy(engine = engine))
    }

    /** Commits the cleanup strength (0 to 1) when the slider is let go. */
    fun setVoiceStrength(strength: Float) {
        val current = state.value.clip?.audio?.voiceCleanup ?: return
        setVoiceCleanup(current.copy(strength = strength))
    }

    /** Gives every video clip with sound this clip's cleanup (or none), as one undo step. */
    fun applyVoiceToAll() {
        val clip = state.value.clip ?: return
        val current = session ?: return
        current.edit(ApplyVoiceCleanupToAll(clip.audio.voiceCleanup))
        val project = current.project
        appScope.launch(Dispatchers.IO) { voices.requestAll(project) }
    }

    private fun setVoiceCleanup(cleanup: VoiceCleanup?) {
        val clip = state.value.clip ?: return
        setAudio(clip.audio.copy(voiceCleanup = cleanup))
        val asset = asset() ?: return
        if (cleanup != null) appScope.launch(Dispatchers.IO) { voices.request(asset, cleanup) }
    }

    private fun asset(): MediaAsset? {
        val current = state.value
        return current.clip?.source?.let { current.project?.asset(it.mediaId) }
    }

    /**
     * Follows the cleanup: DeepFilterNet's download, the clip's progress, and each time the
     * cleaned sound changes, its waveform and a new loudness measurement.
     */
    private suspend fun watchVoice() = coroutineScope {
        launch {
            models.models.collect { all ->
                val status = all.firstOrNull { it.model == ModelCatalog.deepFilterNet }?.status
                    ?: return@collect
                mutableState.update { it.copy(voice = it.voice.copy(deepFilterNet = status)) }
                val pending = pendingEngine
                if (status is ModelStatus.Installed && pending != null) chooseEngine(pending)
            }
        }
        launch {
            combine(voices.running, state) { running, current ->
                val cleanup = current.clip?.audio?.voiceCleanup
                val asset = asset()
                if (cleanup == null || asset == null) {
                    null
                } else {
                    running[VoiceCleaner.key(asset, cleanup.engine)]
                }
            }.distinctUntilChanged().collect { progress ->
                mutableState.update { it.copy(voice = it.voice.copy(cleaning = progress)) }
            }
        }
        combine(cache.voiceVersion, state) { version, current ->
            Triple(version, current.clip?.audio?.voiceCleanup, current.project != null)
        }.filter { it.third }
            .distinctUntilChanged()
            .collectLatest { (_, cleanup) ->
                val project = state.value.project ?: return@collectLatest
                loadCleanedWaveform(cleanup)
                measure(project)
            }
    }

    private suspend fun loadCleanedWaveform(cleanup: VoiceCleanup?) {
        val asset = asset()
        val mix = if (cleanup != null && asset != null) voices.mix(asset, cleanup) else null
        val waveform = if (mix == null || asset == null) {
            null
        } else {
            withContext(Dispatchers.IO) {
                runCatching {
                    Waveforms.waveform(
                        context,
                        mix.path,
                        asset.durationUs,
                        cache.voiceWaveform(mix),
                    )
                }.getOrNull()
            }
        }
        mutableState.update { it.copy(voice = it.voice.copy(cleanedWaveform = waveform)) }
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
     * Measures the clip's stretch of what it plays: its cleaned voice once that is ready, its
     * original media otherwise. A normalized clip that was trimmed (or cleaned up) since it was
     * last measured gets the new measurement, so the export stays on target.
     */
    private suspend fun measure(project: Project) {
        val clip = clipId?.let { project.findClip(it)?.second }
        val source = clip?.source
        val asset = source?.let { project.asset(it.mediaId) }
        var recorded: Loudness.Measurement? = null
        val measured = if (asset == null || (clip.type == ClipType.Video && !asset.hasAudio)) {
            null
        } else {
            withContext(Dispatchers.Default) {
                val media = ProjectMediaResolver(context, cache, forExport = true, maxImageSide = 0)
                val original = media.source(asset) ?: return@withContext null
                val endUs = source.outPointUs
                fun measure(path: String) = runCatching {
                    Loudness.measure(context, path, source.inPointUs, endUs)
                }.getOrNull()
                recorded = measure(original)
                val voice = clip.audio.voiceCleanup?.let { voices.mix(asset, it) }
                if (voice == null) recorded else measure(voice.path)
            }
        }
        mutableState.update {
            it.copy(measured = measured, recorded = recorded, measuring = false)
        }
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
