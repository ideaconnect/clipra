package tech.idct.clipra.feature.captions

import org.junit.Assert.assertEquals
import org.junit.Test

class CaptionsBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = CaptionsBoards.all
        val expected = listOf("CaptionsSetup", "CaptionsEdit", "Transcript")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
