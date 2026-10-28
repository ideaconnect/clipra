package tech.idct.clipra.core.designsystem.placeholder

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class BoardTest {
    private object FirstRoute
    private object SecondRoute

    @Test
    fun boardsAreEqualByIdTitleAndRoute() {
        assertEquals(Board("Main", 1, FirstRoute), Board("Main", 1, FirstRoute))
        assertNotEquals(Board("Main", 1, FirstRoute), Board("Main", 1, SecondRoute))
    }
}
