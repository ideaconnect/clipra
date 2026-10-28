package tech.idct.clipra.core.engine.render

/**
 * Pauses an export: while closed, the compositor waits before drawing the next frame, so the
 * whole pipeline stops without losing anything; opening it carries on.
 */
class PauseGate {
    @Volatile
    var closed: Boolean = false
        private set

    private val lock = Object()

    fun close() {
        closed = true
    }

    fun open() = synchronized(lock) {
        closed = false
        lock.notifyAll()
    }

    /** Blocks the calling thread while the gate is closed. */
    fun awaitOpen() = synchronized(lock) {
        while (closed) lock.wait(WAIT_MS)
    }

    private companion object {
        const val WAIT_MS = 200L
    }
}
