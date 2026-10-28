package tech.idct.clipra.core.model.edit

import tech.idct.clipra.core.model.Project

/**
 * Undo and redo with unlimited steps per session. Projects are immutable, so each step keeps the
 * project before and after a command: undoing restores the one before (the command's inverse)
 * and redoing the one after.
 */
class EditHistory(initial: Project) {
    var project: Project = initial
        private set

    private val undoStack = ArrayDeque<Project>()
    private val redoStack = ArrayDeque<Project>()

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()

    /** Applies [command]; throws [EditRejected] and leaves everything as it was when refused. */
    fun apply(command: EditCommand): Project {
        val next = command.apply(project)
        if (next != project) {
            undoStack.addLast(project)
            redoStack.clear()
            project = next
        }
        return project
    }

    fun undo(): Project {
        if (canUndo) {
            redoStack.addLast(project)
            project = undoStack.removeLast()
        }
        return project
    }

    fun redo(): Project {
        if (canRedo) {
            undoStack.addLast(project)
            project = redoStack.removeLast()
        }
        return project
    }
}
