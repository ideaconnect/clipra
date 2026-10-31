package tech.idct.clipra.core.data.project

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import tech.idct.clipra.core.model.Project

/**
 * Saves a project after edits: once edits pause for [quietMs], and at the latest [maxDelayMs]
 * after the first unsaved edit even while edits keep coming. Killing the app therefore loses at
 * most about a second of edits (#10). [flush] saves the latest edit at once, for example when the
 * app goes to the background.
 */
class ProjectAutosaver(
    scope: CoroutineScope,
    private val save: suspend (Project) -> Unit,
    private val nowMs: () -> Long,
    private val quietMs: Long = 300,
    private val maxDelayMs: Long = 1_000,
    private val retryMs: Long = 5_000,
) {
    private val edits = Channel<Project>(Channel.CONFLATED)
    private val lock = Mutex()
    private val mutableFailing = MutableStateFlow(false)

    /**
     * Whether the last save failed, as when the phone's storage is full. The edits stay unsaved
     * and are tried again with each edit and every [retryMs] until a save works; a failed save
     * never ends the app.
     */
    val failing: StateFlow<Boolean> = mutableFailing.asStateFlow()

    @Volatile
    private var unsaved: Project? = null

    private val job = scope.launch { run() }

    fun submit(project: Project) {
        unsaved = project
        edits.trySend(project)
    }

    suspend fun flush() {
        lock.withLock { unsaved?.let { saveNow(it) } }
    }

    /** Saves what is left and stops. */
    suspend fun close() {
        flush()
        job.cancel()
    }

    private suspend fun run() {
        while (true) {
            // After a failed save the latest edit is tried again a while later, edits or not.
            if (failing.value) withTimeoutOrNull(retryMs) { edits.receive() } else edits.receive()
            val deadline = nowMs() + maxDelayMs
            while (true) {
                val wait = minOf(quietMs, deadline - nowMs())
                if (wait <= 0) break
                withTimeoutOrNull(wait) { edits.receive() } ?: break
            }
            // A flush may have saved it already.
            lock.withLock { unsaved?.let { saveNow(it) } }
        }
    }

    private suspend fun saveNow(project: Project) {
        try {
            save(project)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            mutableFailing.value = true
            return
        }
        mutableFailing.value = false
        if (unsaved === project) unsaved = null
    }
}
