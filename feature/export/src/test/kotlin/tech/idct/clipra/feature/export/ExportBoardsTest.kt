package tech.idct.clipra.feature.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = ExportBoards.all
        val expected = listOf("Export", "ExportProgress", "ExportDone")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
