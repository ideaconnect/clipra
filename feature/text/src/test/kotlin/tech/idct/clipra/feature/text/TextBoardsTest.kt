package tech.idct.clipra.feature.text

import org.junit.Assert.assertEquals
import org.junit.Test

class TextBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = TextBoards.all
        val expected = listOf("Text")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
