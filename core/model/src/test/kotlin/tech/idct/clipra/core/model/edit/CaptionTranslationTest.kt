package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word

/** Captions in more languages (#47): copies in step with the original, and the chips. */
class CaptionTranslationTest {
    private var ids = 0
    private val newId = { "t${ids++}" }

    private fun caption(id: String, start: Long, duration: Long, vararg words: String) = Clip(
        id = id,
        type = ClipType.Caption,
        startUs = start,
        durationUs = duration,
        caption = CaptionSpec(
            words = words.mapIndexed { i, text -> Word(text, i * 100_000L, (i + 1) * 100_000L) },
            style = CaptionStyle.Karaoke,
        ),
    )

    private val project = Project(
        id = "p",
        name = "Lisbon",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip(
                        "src",
                        ClipType.Video,
                        0,
                        10_000_000,
                        MediaRef("m", 0, 10_000_000),
                    ),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    caption("c1", 0, 2_000_000, "We", "made", "it"),
                    caption("c2", 3_000_000, 1_000_000, "Hello"),
                ),
            ),
        ),
        links = listOf(LinkGroup("g", listOf("src", "c1", "c2"))),
        transcripts = listOf(Transcript("src", language = "en")),
    )

    private val polish = mapOf("c1" to "Udało nam się", "c2" to "Cześć")

    @Test
    fun aTranslationIsACopyOfTheCaptionsOnANewLayerShownInsteadOfThem() {
        val edited = TranslateCaptions("pl", polish, newId).apply(project)

        assertEquals(3, edited.layers.size)
        val translated = edited.layers.last().clips
        assertEquals(listOf(0L, 3_000_000L), translated.map { it.startUs })
        assertEquals(listOf(2_000_000L, 1_000_000L), translated.map { it.durationUs })
        assertEquals(listOf("Udało nam się", "Cześć"), translated.map { it.caption!!.text })
        assertTrue(translated.all { it.caption!!.language == "pl" })
        // The look is the original's; the words share the caption's time by length.
        assertEquals(CaptionStyle.Karaoke, translated[0].caption!!.style)
        assertEquals(2_000_000L, translated[0].caption!!.words.last().endUs)
        // In step with the source: same link group.
        assertTrue(edited.linkedPartners("src").containsAll(translated.map { it.id }))
        // The translation shows; the captions as heard hide.
        assertTrue(edited.layers[1].hidden)
        assertFalse(edited.layers[2].hidden)
        assertEquals("pl", edited.shownCaptionLanguage())
        assertEquals(listOf("pl"), edited.translatedCaptionLanguages())
        assertEquals("en", edited.spokenCaptionLanguage())
    }

    @Test
    fun theChipsSwitchBetweenLanguages() {
        val translated = TranslateCaptions("pl", polish, newId).apply(project)

        val heard = ShowCaptionLanguage("").apply(translated)
        assertFalse(heard.layers[1].hidden)
        assertTrue(heard.layers[2].hidden)
        assertEquals("", heard.shownCaptionLanguage())
        // The layer with the video is left alone.
        assertFalse(heard.layers[0].hidden)
        assertEquals(listOf("c1", "c2"), heard.captionsIn("").map { it.id })
    }

    @Test
    fun translatingAgainReplacesTheEarlierTranslation() {
        val first = TranslateCaptions("pl", polish, newId).apply(project)
        val again = TranslateCaptions("pl", mapOf("c1" to "Jesteśmy"), newId).apply(first)

        assertEquals(3, again.layers.size)
        assertEquals(listOf("Jesteśmy"), again.captionsIn("pl").map { it.caption!!.text })
        // The replaced copies leave the link group: the source, two captions and one copy.
        assertEquals(4, again.links.single().clipIds.size)
    }

    @Test
    fun makingTheCaptionsAgainTakesTheTranslationsWithThem() {
        val translated = TranslateCaptions("pl", polish, newId).apply(project)
        assertEquals(4, translated.linkedPartners("src").size)

        val again = CreateCaptions(listOf("src"), newId = newId).apply(translated)

        assertTrue(again.captionsIn("pl").isEmpty())
        assertNull(again.captionsIn("").firstOrNull())
    }
}
