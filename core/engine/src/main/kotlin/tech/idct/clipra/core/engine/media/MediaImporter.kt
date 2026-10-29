package tech.idct.clipra.core.engine.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Imports picked media: keeps read access to it (the photo picker and the system file picker
 * need no storage permission), inspects it, makes a thumbnail and queues the proxy, and starts
 * the waveform of anything with sound, so the timeline has it by the time it is shown. The clip
 * is editable at once; preview reads the original until the proxy is ready.
 */
@Singleton
class MediaImporter @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun import(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        } catch (_: SecurityException) {
            // Not every provider offers lasting access; the media then lasts as long as the grant.
        }
        val asset = MediaInspector.inspect(context, uri)
        Thumbnails.thumbnail(context, asset, cache)
        if (ProxyGenerator.needsProxy(asset)) ProxyWorker.enqueue(context, asset)
        if (asset.hasAudio || asset.kind == MediaKind.Audio) {
            background.launch {
                val originals = ProjectMediaResolver(context, cache, forExport = true, 0)
                runCatching { Waveforms.waveform(context, asset, cache, originals) }
            }
        }
        asset
    }
}

/** Makes a proxy in the background, once per media id. */
@HiltWorker
class ProxyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cache: MediaCache,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.failure()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.failure()
        val proxy = cache.proxy(mediaId)
        if (proxy.exists()) return Result.success()
        return try {
            ProxyGenerator.generate(applicationContext, uri, proxy)
            Result.success()
        } catch (_: Exception) {
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.failure()
        }
    }

    companion object {
        private const val KEY_MEDIA_ID = "mediaId"
        private const val KEY_URI = "uri"
        private const val MAX_ATTEMPTS = 2

        fun enqueue(context: Context, asset: MediaAsset) {
            val request = OneTimeWorkRequestBuilder<ProxyWorker>()
                .setInputData(workDataOf(KEY_MEDIA_ID to asset.id, KEY_URI to asset.uri))
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("proxy-${asset.id}", ExistingWorkPolicy.KEEP, request)
        }
    }
}
