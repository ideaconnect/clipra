package tech.idct.clipra.core.engine.export

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tech.idct.clipra.core.engine.media.Cutouts
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.engine.render.PauseGate
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Srt
import tech.idct.clipra.core.model.export.CaptionMode
import tech.idct.clipra.core.model.export.ExportSettings
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** Where the one export at a time stands. */
sealed interface ExportState {
    data object Idle : ExportState

    data class Running(
        val projectId: String,
        val projectName: String,
        val fileName: String,
        val settings: ExportSettings,
        val width: Int,
        val height: Int,
        val progress: Float,
        val framesTotal: Int,
        val paused: Boolean,
        /** Seconds left at the current pace; null until there is a pace to measure. */
        val secondsLeft: Int?,
    ) : ExportState {
        val framesDone: Int get() = (framesTotal * progress).roundToInt()
    }

    data class Done(
        val projectId: String,
        val uri: String,
        val fileName: String,
        val sizeBytes: Long,
        val durationUs: Long,
        val settings: ExportSettings,
        val width: Int,
        val height: Int,
        /** The files saved next to the video (#59). */
        val extras: List<SavedExtra> = emptyList(),
        /** Whether the video has captions drawn into it. */
        val captionsBurnedIn: Boolean = false,
    ) : ExportState

    data class Failed(val projectId: String, val failure: ExportFailure) : ExportState
}

/** What the ad rules (M9) listen to. */
enum class ExportEvent { Started, Finished, Failed, Cancelled }

/**
 * Runs exports in the app's scope, so they carry on when the screen goes or the app is in the
 * background (the export service keeps the process alive). One export at a time.
 */
@Singleton
class ExportManager @Inject constructor(
    @param:ApplicationContext private val context: Context,
    cache: MediaCache,
    voices: VoiceCleaner,
    cutouts: Cutouts,
) {
    private val renderer = ExportRenderer(context, cache, voices, cutouts)
    private val extras = ExportExtras(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mutableState = MutableStateFlow<ExportState>(ExportState.Idle)
    private val mutableEvents = MutableSharedFlow<ExportEvent>(extraBufferCapacity = 8)
    private val progressVisible = MutableStateFlow(false)
    private var job: Job? = null
    private var gate = PauseGate()
    private var runningMs = 0L
    private var resumedAt = 0L

    val state: StateFlow<ExportState> = mutableState.asStateFlow()
    val events: SharedFlow<ExportEvent> = mutableEvents.asSharedFlow()

    /** Whether the progress screen is on screen; the ad rules need to know (M9). */
    val progressScreenVisible: StateFlow<Boolean> = progressVisible.asStateFlow()

    fun setProgressScreenVisible(visible: Boolean) {
        progressVisible.value = visible
    }

    fun start(project: Project, settings: ExportSettings) {
        if (mutableState.value is ExportState.Running) return
        val size = settings.outputSize(project)
        val running = ExportState.Running(
            projectId = project.id,
            projectName = project.name,
            fileName = fileName(project.name),
            settings = settings,
            width = size.width,
            height = size.height,
            progress = 0f,
            framesTotal = (project.durationUs * settings.frameRate.fps / 1_000_000).toInt(),
            paused = false,
            secondsLeft = null,
        )
        mutableState.value = running
        mutableEvents.tryEmit(ExportEvent.Started)
        gate = PauseGate()
        runningMs = 0
        resumedAt = SystemClock.elapsedRealtime()
        job = scope.launch {
            try {
                val rendered = renderer.render(project, settings, gate) { progress ->
                    onProgress(progress)
                }
                val size = rendered.file.length()
                val uri: Uri
                val name: String
                val saved: List<SavedExtra>
                try {
                    uri = renderer.publish(rendered.file, running.fileName)
                    // MediaStore adds a number when the name is taken; the extras follow it.
                    name = extras.displayName(uri) ?: running.fileName
                    saved = extras.save(
                        project,
                        settings,
                        rendered.file,
                        name.substringBeforeLast('.'),
                    ) { renderer.cover(project, settings) }
                } finally {
                    rendered.file.delete()
                }
                mutableState.value = ExportState.Done(
                    projectId = project.id,
                    uri = uri.toString(),
                    fileName = name,
                    sizeBytes = size,
                    durationUs = project.durationUs,
                    settings = settings,
                    width = rendered.width,
                    height = rendered.height,
                    extras = saved,
                    captionsBurnedIn = settings.captions == CaptionMode.BurnIn &&
                        Srt.cues(project).isNotEmpty(),
                )
                mutableEvents.tryEmit(ExportEvent.Finished)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (failed: ExportFailedException) {
                mutableState.value = ExportState.Failed(project.id, failed.failure)
                mutableEvents.tryEmit(ExportEvent.Failed)
            } catch (_: Exception) {
                mutableState.value = ExportState.Failed(project.id, ExportFailure.Other)
                mutableEvents.tryEmit(ExportEvent.Failed)
            }
        }
    }

    fun pause() {
        val running = mutableState.value as? ExportState.Running ?: return
        if (running.paused) return
        gate.close()
        runningMs += SystemClock.elapsedRealtime() - resumedAt
        mutableState.value = running.copy(paused = true)
    }

    fun resume() {
        val running = mutableState.value as? ExportState.Running ?: return
        if (!running.paused) return
        resumedAt = SystemClock.elapsedRealtime()
        gate.open()
        mutableState.value = running.copy(paused = false)
    }

    fun cancel() {
        if (mutableState.value !is ExportState.Running) return
        job?.cancel()
        gate.open()
        mutableState.value = ExportState.Idle
        mutableEvents.tryEmit(ExportEvent.Cancelled)
    }

    /** Clears a finished or failed export once it has been shown. */
    fun dismiss() {
        if (mutableState.value !is ExportState.Running) mutableState.value = ExportState.Idle
    }

    private fun onProgress(progress: Float) {
        mutableState.update { state ->
            val running = state as? ExportState.Running ?: return@update state
            if (running.paused) return@update running
            val elapsedMs = runningMs + SystemClock.elapsedRealtime() - resumedAt
            val left = if (progress >
                0.02f
            ) {
                ((elapsedMs / progress) * (1 - progress) / 1000).roundToInt()
            } else {
                null
            }
            running.copy(progress = progress, secondsLeft = left)
        }
    }

    companion object {
        /** "lisbon-weekend.mp4": the project name as a file name; MediaStore adds a number if taken. */
        fun fileName(projectName: String): String {
            // "ł" has no decomposition, so it is mapped by hand.
            val latin = projectName.replace('ł', 'l').replace('Ł', 'L')
            val plain = Normalizer.normalize(
                latin,
                Normalizer.Form.NFD,
            ).replace(Regex("\\p{M}"), "")
            val slug = plain.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').take(60)
            return (slug.ifEmpty { "clipra" }) + ".mp4"
        }
    }
}
