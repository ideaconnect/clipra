package tech.idct.clipra.core.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
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
) {
    private val edits = Channel<Project>(Channel.CONFLATED)
    private val lock = Mutex()

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
            edits.receive()
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
        save(project)
        if (unsaved === project) unsaved = null
    }
}
