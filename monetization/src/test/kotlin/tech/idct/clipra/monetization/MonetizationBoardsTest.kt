package tech.idct.clipra.monetization

import org.junit.Assert.assertEquals
import org.junit.Test

class MonetizationBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = MonetizationBoards.all
        val expected = listOf("Pro", "AdStart", "AdFinish")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
