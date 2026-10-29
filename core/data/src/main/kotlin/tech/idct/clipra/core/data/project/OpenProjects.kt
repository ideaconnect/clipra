package tech.idct.clipra.core.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import tech.idct.clipra.core.data.di.ApplicationScope
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The projects open for editing. The editor and the tool screens it opens (Filters,
 * Transitions, Text and the others, each in its own feature module) share one [ProjectSession]
 * per project, so their edits land in the same undo history and every screen sees them. A
 * session closes, saving what is left, when its last user releases it.
 */
@Singleton
class OpenProjects @Inject constructor(
    private val repository: ProjectRepository,
    @param:ApplicationScope private val scope: CoroutineScope,
) {
    private val mutex = Mutex()
    private val open = HashMap<String, Entry>()

    private class Entry(val session: ProjectSession, var users: Int)

    /** The session of project [id], opening it if no one has; null if the project is gone. */
    suspend fun acquire(id: String): ProjectSession? = mutex.withLock {
        open[id]?.let { entry ->
            entry.users++
            return entry.session
        }
        val session = repository.open(id, scope) ?: return null
        open[id] = Entry(session, 1)
        session
    }

    /** Hands back a session from [acquire]; the last release saves and closes it. */
    suspend fun release(id: String) {
        val closing = mutex.withLock {
            val entry = open[id] ?: return
            entry.users--
            if (entry.users > 0) return
            open.remove(id)
            entry.session
        }
        closing.close()
    }
}
