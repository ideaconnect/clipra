package tech.idct.clipra

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Play store listing and release notes in `src/main/play` (#78): every language has every
 * text, within Play's limits, in the repository's style.
 */
class StoreListingTest {
    private val play = File("src/main/play")
    private val languages = listOf("en-US", "pl-PL")

    @Test
    fun everyLanguageHasTheListingWithinPlayLimits() {
        for (language in languages) {
            for ((name, limit) in LISTING_LIMITS) {
                val text = read("listings/$language/$name.txt")
                assertTrue("$language $name is empty", text.isNotBlank())
                assertTrue("$language $name has ${text.length} of $limit", text.length <= limit)
            }
        }
    }

    @Test
    fun everyLanguageHasReleaseNotesWithinPlayLimits() {
        for (language in languages) {
            val notes = read("release-notes/$language/default.txt")
            assertTrue("$language notes are empty", notes.isNotBlank())
            assertTrue("$language notes have ${notes.length}", notes.length <= NOTES_LIMIT)
        }
    }

    /** The graphics are only in en-US: other languages show them unless they have their own. */
    @Test
    fun theLanguagesHaveTheSameTexts() {
        val en = filesOf("en-US")
        for (language in languages) assertEquals(language, en, filesOf(language))
    }

    @Test
    fun noTextHasAnEmDashOrAFixedPrice() {
        play.walkTopDown().filter { it.extension == "txt" }.forEach { file ->
            val text = file.readText()
            assertTrue("$file has an em dash", '\u2014' !in text)
            // The price always comes from Play Billing (CLAUDE.md, placeholders).
            assertTrue("$file names a price", !PRICE.containsMatchIn(text))
        }
    }

    private fun read(path: String): String = File(play, path).readText().trimEnd('\n')

    private fun filesOf(language: String): Set<String> = listOf("listings", "release-notes")
        .flatMap { folder ->
            val root = File(play, "$folder/$language")
            root.walkTopDown().filter { it.extension == "txt" }
                .map { "$folder/" + it.relativeTo(root).invariantSeparatorsPath }
                .toList()
        }
        .toSet()

    private companion object {
        val LISTING_LIMITS = listOf(
            "title" to 30,
            "short-description" to 80,
            "full-description" to 4000,
        )
        const val NOTES_LIMIT = 500
        val PRICE = Regex("""\d+([.,]\d+)? ?(PLN|zł)""")
    }
}
