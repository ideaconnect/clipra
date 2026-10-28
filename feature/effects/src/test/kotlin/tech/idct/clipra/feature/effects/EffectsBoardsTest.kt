package tech.idct.clipra.feature.effects

import org.junit.Assert.assertEquals
import org.junit.Test

class EffectsBoardsTest {
    @Test
    fun boardsMatchTheDesignAndHaveTheirOwnRoutes() {
        val boards = EffectsBoards.all
        val expected = listOf("Filters", "Transitions", "Masks", "Color", "Cutout", "Reframe")
        assertEquals(expected, boards.map { it.id })
        assertEquals(boards.size, boards.map { it.route }.toSet().size)
    }
}
