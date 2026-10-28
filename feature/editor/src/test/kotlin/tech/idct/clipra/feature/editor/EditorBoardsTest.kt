package tech.idct.clipra.feature.editor

import org.junit.Assert.assertEquals
import org.junit.Test

class EditorBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = EditorBoards.all
        val expected = listOf("Editor", "Layers")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
