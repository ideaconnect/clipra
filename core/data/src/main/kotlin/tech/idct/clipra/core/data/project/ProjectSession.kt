package tech.idct.clipra.core.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.updateAndGet
import tech.idct.clipra.core.model.Project

/**
 * An open project. Every committed change goes through [update] and is autosaved; the edit
 * commands and undo stack (#15) build on it.
 */
class ProjectSession(
    initial: Project,
    scope: CoroutineScope,
    save: suspend (Project) -> Unit,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val state = MutableStateFlow(initial)
    private val autosaver = ProjectAutosaver(scope, save, nowMs)

    val project: StateFlow<Project> = state.asStateFlow()

    fun update(change: (Project) -> Project) {
        val next = state.updateAndGet(change)
        autosaver.submit(next)
    }

    /** Saves any unsaved change now, for example when the app goes to the background. */
    suspend fun flush() = autosaver.flush()
}
