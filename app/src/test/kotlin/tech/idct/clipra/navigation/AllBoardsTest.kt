package tech.idct.clipra.navigation

import org.junit.Assert.assertEquals
import org.junit.Test
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.feature.models.ModelsBoards
import java.io.File

class AllBoardsTest {
    @Test
    fun everyPhoneBoardInTheDesignHasADestination() {
        assertEquals(phoneBoardsInDesignReadme(), AllBoards.phone.map { it.id })
    }

    @Test
    fun everyPhoneBoardIsReachableFromTheFirstRun() {
        val reached = mutableSetOf<Board>()
        val queue = ArrayDeque(listOf(ModelsBoards.onboarding))
        while (queue.isNotEmpty()) {
            val board = queue.removeFirst()
            if (reached.add(board)) queue += placeholderLinks(board, debug = false)
        }
        assertEquals(emptyList<String>(), AllBoards.phone.filterNot { it in reached }.map { it.id })
    }

    /** The board names in the "Board index" tables of design/README.md, up to the tablet boards. */
    private fun phoneBoardsInDesignReadme(): List<String> {
        val lines = File("../design/README.md").readLines()
        val start = lines.indexOfFirst { it.startsWith("## Board index") }
        val end = lines.indexOfFirst { it.startsWith("### Tablet") }
        val row = Regex("""^\| `(\w+)` \|""")
        return lines.subList(start, end).mapNotNull { row.find(it)?.groupValues?.get(1) }
    }
}
