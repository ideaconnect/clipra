package tech.idct.clipra.feature.models

import org.junit.Assert.assertEquals
import org.junit.Test

class ModelsBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = ModelsBoards.all
        val expected = listOf("Onboarding", "Models")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
