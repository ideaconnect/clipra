package tech.idct.clipra.core.models

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import java.io.IOException

/**
 * Downloads one model (#43) in the background with a progress notification. Network errors
 * retry with backoff and resume where they stopped; a failed check or a missing source fails
 * with [KEY_ERROR] set.
 */
@HiltWorker
class ModelDownloadWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val store: ModelStore,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val model = inputData.getString(KEY_MODEL)?.let(ModelCatalog::byId)
            ?: return Result.failure()
        // Without it (started from the background on Android 12 and later) the download still
        // runs, without a notification, and resumes if the system stops it.
        val foreground = runCatching { setForeground(foregroundInfo(model, 0)) }.isSuccess
        val total = model.downloadBytes
        var reported = 0L
        return try {
            withContext(Dispatchers.IO) {
                ModelDownloader(store).download(
                    model,
                    onProgress = { bytes ->
                        val now = SystemClock.elapsedRealtime()
                        if (now - reported >= PROGRESS_MS || bytes == total) {
                            reported = now
                            setProgressAsync(workDataOf(KEY_BYTES to bytes))
                            if (foreground) notify(model, bytes)
                        }
                    },
                    stopped = { isStopped },
                )
            }
            Result.success()
        } catch (e: CorruptDownloadException) {
            Result.failure(workDataOf(KEY_ERROR to ERROR_CORRUPT, KEY_MESSAGE to e.message))
        } catch (e: SourceUnavailableException) {
            Result.failure(workDataOf(KEY_ERROR to ERROR_UNAVAILABLE, KEY_MESSAGE to e.message))
        } catch (_: IOException) {
            Result.retry()
        }
    }

    private fun notify(model: AiModel, bytes: Long) {
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        runCatching { manager.notify(notificationId(model), notification(model, bytes)) }
    }

    private fun foregroundInfo(model: AiModel, bytes: Long) = ForegroundInfo(
        notificationId(model),
        notification(model, bytes),
        ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
    )

    private fun notification(model: AiModel, bytes: Long): android.app.Notification {
        val context = applicationContext
        createChannel(context)
        val total = model.downloadBytes.coerceAtLeast(1)
        val percent = (bytes * 100 / total).toInt()
        return NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(ClipraIcons.Download)
            .setContentTitle(context.getString(R.string.models_notification_title, model.title()))
            .setProgress(100, percent, bytes == 0L)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    companion object {
        const val KEY_MODEL = "model"
        const val KEY_BYTES = "bytes"
        const val KEY_ERROR = "error"
        const val KEY_MESSAGE = "message"
        const val ERROR_CORRUPT = "corrupt"
        const val ERROR_UNAVAILABLE = "unavailable"

        private const val CHANNEL = "model-downloads"
        private const val PROGRESS_MS = 500L

        private fun notificationId(model: AiModel) =
            NOTIFICATION_BASE + ModelCatalog.all.indexOf(model)

        private const val NOTIFICATION_BASE = 4000

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.models_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }
}
