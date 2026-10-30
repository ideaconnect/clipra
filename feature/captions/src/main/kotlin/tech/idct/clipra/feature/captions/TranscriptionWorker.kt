package tech.idct.clipra.feature.captions

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.engine.media.Transcriber
import tech.idct.clipra.core.model.CaptionOptions
import tech.idct.clipra.core.model.edit.AllOf
import tech.idct.clipra.core.model.edit.CreateCaptions
import tech.idct.clipra.core.model.edit.SetTranscripts
import tech.idct.clipra.core.models.ModelCatalog

/** What Auto captions should transcribe, and how (#44). */
data class TranscriptionRequest(
    val projectId: String,
    val clipIds: List<String>,
    val modelId: String,
    val language: String,
    val translate: Boolean,
    val options: CaptionOptions,
)

/** Where a project's transcription stands. */
sealed interface TranscriptionState {
    data object Idle : TranscriptionState

    data class Running(val progress: Float) : TranscriptionState

    /** Done: [words] were stored with the project. */
    data class Done(val words: Int) : TranscriptionState

    data object Failed : TranscriptionState
}

/**
 * Transcribes a project's clips in the background (#44), so it carries on when the screen goes
 * or the app is in the background, with a progress notification. The transcripts and the
 * captions made from them (#45) go into the project as one undo step, through the editor's
 * shared session.
 */
@HiltWorker
class TranscriptionWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val projects: OpenProjects,
    private val transcriber: Transcriber,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val projectId = inputData.getString(KEY_PROJECT) ?: return Result.failure()
        val model = inputData.getString(KEY_MODEL)?.let(ModelCatalog::byId)
            ?: return Result.failure()
        val clipIds = inputData.getStringArray(KEY_CLIPS)?.toList().orEmpty()
        val options = CaptionOptions(
            highlightWords = inputData.getBoolean(KEY_HIGHLIGHT, true),
            removeFillers = inputData.getBoolean(KEY_FILLERS, false),
            newLayer = inputData.getBoolean(KEY_NEW_LAYER, true),
        )
        val foreground = runCatching { setForeground(foregroundInfo(0)) }.isSuccess
        val session = projects.acquire(projectId) ?: return Result.failure()
        var reported = 0L
        return try {
            val transcripts = withContext(Dispatchers.Default) {
                transcriber.transcribe(
                    project = session.project,
                    clipIds = clipIds,
                    model = model,
                    language = inputData.getString(KEY_LANGUAGE) ?: Transcriber.AUTO,
                    translate = inputData.getBoolean(KEY_TRANSLATE, false),
                    onProgress = { progress ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - reported >= PROGRESS_MS) {
                            reported = now
                            setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                            if (foreground) notify((progress * 100).toInt())
                        }
                    },
                    stopped = { isStopped },
                )
            }
            session.edit(AllOf(SetTranscripts(transcripts, options), CreateCaptions(clipIds)))
            Result.success(workDataOf(KEY_WORDS to transcripts.sumOf { it.words.size }))
        } catch (_: Transcriber.Cancelled) {
            Result.failure()
        } finally {
            projects.release(projectId)
        }
    }

    private fun notify(percent: Int) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(NOTIFICATION_ID, notification(percent)) }
    }

    private fun foregroundInfo(percent: Int): ForegroundInfo {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        return ForegroundInfo(NOTIFICATION_ID, notification(percent), type)
    }

    private fun notification(percent: Int): Notification {
        val context = applicationContext
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.captions_notification_channel),
            NotificationManager.IMPORTANCE_LOW,
        )
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(ClipraIcons.Captions)
            .setContentTitle(context.getString(R.string.captions_notification_title))
            .setProgress(100, percent, percent == 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val KEY_PROJECT = "project"
        private const val KEY_CLIPS = "clips"
        private const val KEY_MODEL = "model"
        private const val KEY_LANGUAGE = "language"
        private const val KEY_TRANSLATE = "translate"
        private const val KEY_HIGHLIGHT = "highlight"
        private const val KEY_FILLERS = "fillers"
        private const val KEY_NEW_LAYER = "newLayer"
        private const val KEY_PROGRESS = "progress"
        private const val KEY_WORDS = "words"
        private const val CHANNEL = "captions"
        private const val NOTIFICATION_ID = 4100
        private const val PROGRESS_MS = 300L

        private fun name(projectId: String) = "transcribe-$projectId"

        /** Starts transcribing, replacing a transcription of the same project. */
        fun start(context: Context, request: TranscriptionRequest) {
            val work = OneTimeWorkRequestBuilder<TranscriptionWorker>()
                .setInputData(
                    workDataOf(
                        KEY_PROJECT to request.projectId,
                        KEY_CLIPS to request.clipIds.toTypedArray(),
                        KEY_MODEL to request.modelId,
                        KEY_LANGUAGE to request.language,
                        KEY_TRANSLATE to request.translate,
                        KEY_HIGHLIGHT to request.options.highlightWords,
                        KEY_FILLERS to request.options.removeFillers,
                        KEY_NEW_LAYER to request.options.newLayer,
                    ),
                )
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(name(request.projectId), ExistingWorkPolicy.REPLACE, work)
        }

        fun cancel(context: Context, projectId: String) {
            WorkManager.getInstance(context).cancelUniqueWork(name(projectId))
        }

        /** The transcription of [projectId], as it runs and once it is done. */
        fun state(context: Context, projectId: String): Flow<TranscriptionState> =
            WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(name(projectId))
                .map { infos ->
                    val info = infos.lastOrNull() ?: return@map TranscriptionState.Idle
                    when (info.state) {
                        WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED ->
                            TranscriptionState.Running(0f)

                        WorkInfo.State.RUNNING ->
                            TranscriptionState.Running(info.progress.getFloat(KEY_PROGRESS, 0f))

                        WorkInfo.State.SUCCEEDED ->
                            TranscriptionState.Done(info.outputData.getInt(KEY_WORDS, 0))

                        WorkInfo.State.FAILED -> TranscriptionState.Failed

                        WorkInfo.State.CANCELLED -> TranscriptionState.Idle
                    }
                }
    }
}
