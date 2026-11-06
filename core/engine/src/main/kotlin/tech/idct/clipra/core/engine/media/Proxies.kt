package tech.idct.clipra.core.engine.media

import android.content.Context
import androidx.work.WorkManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import tech.idct.clipra.core.model.MediaAsset
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The 720p proxies the editor plays. They are queued when media is imported, and again when a
 * project opens and the cache has lost them, as after the system or Settings cleared it (#217).
 * [ProxyWorker] makes them one at a time; a proxy already queued or being made is not queued
 * again, so opening a project twice adds nothing.
 */
@Singleton
class Proxies @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val cache: MediaCache,
) {
    /** The media whose proxy is queued or being made, by id. */
    val making: Flow<Set<String>> = flow {
        emitAll(WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ProxyWorker.QUEUE))
    }.map { works ->
        works.filterNot { it.state.isFinished }
            .flatMap { it.tags }
            .mapNotNull(ProxyWorker::mediaId)
            .toSet()
    }.distinctUntilChanged()

    /**
     * Queues the proxy of [asset], whose original can be read, unless it needs none, it is in
     * the cache, or it is queued or being made. Asks WorkManager and waits for it, so this runs
     * off the main thread.
     */
    fun request(asset: MediaAsset) {
        if (!ProxyGenerator.needsProxy(asset) || cache.proxy(asset.id).exists()) return
        synchronized(this) {
            try {
                val work = WorkManager.getInstance(context)
                val queued = work.getWorkInfosByTag(ProxyWorker.tag(asset.id)).get()
                    .any { !it.state.isFinished }
                if (!queued) ProxyWorker.enqueue(context, asset).result.get()
            } catch (_: Exception) {
                // WorkManager couldn't be read or written, as when storage is full: the
                // preview plays the original, and the proxy is asked for again next time.
            }
        }
    }
}
