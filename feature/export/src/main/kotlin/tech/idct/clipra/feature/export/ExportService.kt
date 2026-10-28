package tech.idct.clipra.feature.export

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import tech.idct.clipra.core.designsystem.icon.ClipraIcons
import tech.idct.clipra.core.engine.export.ExportManager
import tech.idct.clipra.core.engine.export.ExportState
import javax.inject.Inject

/**
 * Keeps an export running in the background (#58): a foreground service with a progress
 * notification (media processing on Android 15 and later, data sync before). When the export
 * ends, it leaves a "Saved to Gallery" notification that opens the Saved screen.
 */
@AndroidEntryPoint
class ExportService : Service() {
    @Inject
    lateinit var exports: ExportManager

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watching = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel(this)
        val type = if (Build.VERSION.SDK_INT >= 35) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING
        } else {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        }
        startForeground(PROGRESS_ID, progressNotification(exports.state.value), type)
        if (!watching) {
            watching = true
            scope.launch {
                exports.state.collect { state ->
                    when (state) {
                        is ExportState.Running -> notify(PROGRESS_ID, progressNotification(state))
                        is ExportState.Done -> finish(doneNotification(state))
                        is ExportState.Failed -> finish(failedNotification())
                        ExportState.Idle -> finish(null)
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun finish(result: Notification?) {
        stopForeground(STOP_FOREGROUND_REMOVE)
        result?.let { notify(RESULT_ID, it) }
        stopSelf()
    }

    private fun notify(id: Int, notification: Notification) {
        getSystemService(NotificationManager::class.java).notify(id, notification)
    }

    private fun progressNotification(state: ExportState): Notification {
        val running = state as? ExportState.Running
        val percent = ((running?.progress ?: 0f) * 100).toInt()
        val title = if (running?.paused ==
            true
        ) {
            R.string.export_notification_paused
        } else {
            R.string.export_notification_running
        }
        return builder()
            .setContentTitle(getString(title))
            .setContentText(running?.fileName.orEmpty())
            .setProgress(100, percent, running == null)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
    }

    private fun doneNotification(state: ExportState.Done): Notification = builder()
        .setContentTitle(getString(R.string.export_notification_done))
        .setContentText(state.fileName)
        .setAutoCancel(true)
        .build()

    private fun failedNotification(): Notification = builder()
        .setContentTitle(getString(R.string.export_notification_failed))
        .setAutoCancel(true)
        .build()

    private fun builder(): NotificationCompat.Builder = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(ClipraIcons.Export)
        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
        .setContentIntent(openExport(this))

    companion object {
        private const val CHANNEL = "exports"
        private const val PROGRESS_ID = 1
        private const val RESULT_ID = 2

        /** Intent extra the app reads to open the export screens. */
        const val EXTRA_OPEN = "tech.idct.clipra.OPEN"
        const val OPEN_EXPORT = "export"

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, ExportService::class.java),
            )
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(
                CHANNEL,
                context.getString(R.string.export_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            context.getSystemService(
                NotificationManager::class.java,
            ).createNotificationChannel(channel)
        }

        private fun openExport(context: Context): PendingIntent {
            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
                .putExtra(EXTRA_OPEN, OPEN_EXPORT)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            return PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }
    }
}
