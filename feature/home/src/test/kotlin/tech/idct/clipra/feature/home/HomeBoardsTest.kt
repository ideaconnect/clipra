package tech.idct.clipra.feature.home

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = HomeBoards.all
        val expected = listOf("Main", "NewProject")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
