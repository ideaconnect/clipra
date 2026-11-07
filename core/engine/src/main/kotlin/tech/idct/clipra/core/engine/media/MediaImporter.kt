package tech.idct.clipra.core.engine.media

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.Operation
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
import java.io.File
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
    private val proxies: Proxies,
) {
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun import(uri: Uri): MediaAsset = withContext(Dispatchers.IO) {
        if (uri.scheme == ContentResolver.SCHEME_CONTENT) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            } catch (e: SecurityException) {
                // Not every provider offers lasting access: the media then lasts only as long as
                // the grant and is missing once it ends (#226), so the log says so.
                Log.w(TAG, "No lasting access to $uri: it is missing once its grant ends", e)
            }
        }
        prepare(MediaInspector.inspect(context, uri))
    }

    /** Imports a voiceover recorded into app storage (#41), named [displayName]. */
    suspend fun importRecording(file: File, displayName: String): MediaAsset =
        withContext(Dispatchers.IO) {
            val asset = MediaInspector.inspect(context, Uri.fromFile(file))
            prepare(asset.copy(kind = MediaKind.Audio, displayName = displayName, recorded = true))
        }

    /**
     * Makes again what the import made and the cache lost (#217), as after the system or
     * Settings cleared it: the thumbnail and the proxy of each of [media], whose originals can
     * be read. Filmstrips and waveforms come back on their own once they are shown. Reads the
     * originals, so this runs off the main thread.
     */
    fun restore(media: List<MediaAsset>) {
        // The thumbnails first, a frame each, so the proxies then have the decoders to themselves.
        for (asset in media) {
            try {
                Thumbnails.thumbnail(context, asset, cache)
            } catch (_: Exception) {
                // As when storage is full: Home and the media bin show no picture for it until
                // it can be made, the next time the project opens.
            }
        }
        media.forEach(proxies::request)
    }

    private fun prepare(asset: MediaAsset): MediaAsset {
        Thumbnails.thumbnail(context, asset, cache)
        proxies.request(asset)
        if (asset.hasAudio || asset.kind == MediaKind.Audio) {
            background.launch {
                val originals = ProjectMediaResolver(context, cache, forExport = true, 0)
                runCatching { Waveforms.waveform(context, asset, cache, originals) }
            }
        }
        return asset
    }

    private companion object {
        const val TAG = "MediaImporter"
    }
}

/**
 * Makes a proxy in the background. Proxies are made one at a time: a phone has only a few video
 * codecs, and several 4K clips picked together would take them all, failing the proxies and
 * leaving none for the preview.
 */
@HiltWorker
class ProxyWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val cache: MediaCache,
) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val mediaId = inputData.getString(KEY_MEDIA_ID) ?: return Result.success()
        val uri = inputData.getString(KEY_URI)?.let(Uri::parse) ?: return Result.success()
        val proxy = cache.proxy(mediaId)
        if (proxy.exists()) return Result.success()
        return try {
            ProxyGenerator.generate(applicationContext, uri, proxy)
            // The preview plays the proxy from its next rebuild.
            cache.voiceReady()
            Result.success()
        } catch (_: Exception) {
            // A failed proxy would fail every proxy queued after it; the editor plays the
            // original instead.
            if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else Result.success()
        }
    }

    companion object {
        private const val KEY_MEDIA_ID = "mediaId"
        private const val KEY_URI = "uri"
        private const val MAX_ATTEMPTS = 2
        private const val TAG_PREFIX = "proxy-"
        const val QUEUE = "proxies"

        /** The tag of the work that makes the proxy of [mediaId] (#217). */
        fun tag(mediaId: String): String = TAG_PREFIX + mediaId

        /** The media id in a proxy work's [tag]; null for its other tags. */
        fun mediaId(tag: String): String? = tag.takeIf { it.startsWith(TAG_PREFIX) }
            ?.removePrefix(TAG_PREFIX)

        /** Queues the proxy of [asset] after the ones queued before; use [Proxies.request]. */
        internal fun enqueue(context: Context, asset: MediaAsset): Operation {
            val request = OneTimeWorkRequestBuilder<ProxyWorker>()
                .setInputData(workDataOf(KEY_MEDIA_ID to asset.id, KEY_URI to asset.uri))
                .addTag(tag(asset.id))
                .build()
            return WorkManager.getInstance(context)
                .enqueueUniqueWork(QUEUE, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }
}
