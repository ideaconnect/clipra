package tech.idct.clipra.core.data.project

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.edit.EditCommand
import tech.idct.clipra.core.model.edit.EditHistory
import tech.idct.clipra.core.model.edit.EditRejected

/** The open project and whether undo and redo are available, for the editor's buttons. */
data class SessionState(val project: Project, val canUndo: Boolean, val canRedo: Boolean)

/**
 * An open project. Every edit is a command applied through the undo history (#15), and every
 * change, undo and redo included, is autosaved.
 */
class ProjectSession(
    initial: Project,
    scope: CoroutineScope,
    save: suspend (Project) -> Unit,
    nowMs: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    private val history = EditHistory(initial)
    private val autosaver = ProjectAutosaver(scope, save, nowMs)
    private val mutableState =
        MutableStateFlow(SessionState(initial, canUndo = false, canRedo = false))

    val state: StateFlow<SessionState> = mutableState.asStateFlow()

    val project: Project get() = state.value.project

    /** Whether edits can't be saved now, as when the phone's storage is full. */
    val saveFailing: StateFlow<Boolean> get() = autosaver.failing

    /** Applies [command]; returns why it was refused, or null when it was applied. */
    fun edit(command: EditCommand): EditRejected? = synchronized(history) {
        try {
            history.apply(command)
        } catch (rejected: EditRejected) {
            return rejected
        }
        publish()
        null
    }

    fun undo() = synchronized(history) {
        history.undo()
        publish()
    }

    fun redo() = synchronized(history) {
        history.redo()
        publish()
    }

    /** Saves any unsaved change now, for example when the app goes to the background. */
    suspend fun flush() = autosaver.flush()

    /** Saves what is left and stops autosaving; the editor calls it when it closes. */
    suspend fun close() = autosaver.close()

    private fun publish() {
        val next = SessionState(history.project, history.canUndo, history.canRedo)
        if (next.project != mutableState.value.project) autosaver.submit(next.project)
        mutableState.value = next
    }
}
