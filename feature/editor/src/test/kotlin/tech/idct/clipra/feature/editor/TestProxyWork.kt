package tech.idct.clipra.feature.editor

import android.content.Context
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import tech.idct.clipra.core.engine.media.ProxyWorker

/**
 * WorkManager for the editor's tests (#217). Robolectric has no video codecs, so the proxy work
 * queued here makes nothing: it stays queued or running until [finish], as a 4K proxy does on a
 * phone for a while.
 */
internal class TestProxyWork(private val context: Context) {
    private val made = CompletableDeferred<Unit>()

    init {
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): Result {
                    made.await()
                    return Result.success()
                }
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
    }

    /** Every proxy work of [mediaId] queued so far, finished or not. */
    fun works(mediaId: String): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosByTag(ProxyWorker.tag(mediaId)).get()

    /** How many proxy works of [mediaId] are queued or running. */
    fun pending(mediaId: String): Int = works(mediaId).count { !it.state.isFinished }

    /** Lets the proxy works end, as when the proxies are made. */
    fun finish() = made.complete(Unit)

    /**
     * Ends every proxy work, once [background] (the editor's work that outlives it) is done, so
     * nothing of this test runs on into the next one.
     */
    fun close(background: CoroutineScope) {
        background.awaitIdle()
        finish()
        repeat(500) {
            val queue = WorkManager.getInstance(context)
                .getWorkInfosForUniqueWork(ProxyWorker.QUEUE).get()
            if (queue.all { it.state.isFinished }) return
            Thread.sleep(10)
        }
        error("The proxy work never ended")
    }
}

/** Waits until the jobs started in this scope have ended. */
internal fun CoroutineScope.awaitIdle() {
    repeat(500) {
        if (coroutineContext[Job]!!.children.none { it.isActive }) return
        Thread.sleep(10)
    }
    error("The background work never ended")
}
