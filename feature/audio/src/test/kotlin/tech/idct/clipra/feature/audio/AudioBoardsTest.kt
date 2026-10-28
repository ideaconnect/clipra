package tech.idct.clipra.feature.audio

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = AudioBoards.all
        val expected = listOf("Audio")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
