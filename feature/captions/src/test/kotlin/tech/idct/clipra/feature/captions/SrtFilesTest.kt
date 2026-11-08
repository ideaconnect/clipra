package tech.idct.clipra.feature.captions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.edit.ShowCaptionLanguage
import tech.idct.clipra.core.model.edit.TranslateCaptions
import java.time.LocalDateTime

/**
 * The name Save .srt suggests (#234): each language and each save gets its own, so Android 10's
 * picker never numbers one after the extension ("Projekt bez nazwy.srt (1)").
 */
class SrtFilesTest {
    private var ids = 0
    private val newId = { "t${ids++}" }

    private val savedAt = LocalDateTime.of(2026, 11, 7, 14, 20, 5)

    private fun caption(id: String, start: Long, text: String) = Clip(
        id = id,
        type = ClipType.Caption,
        startUs = start,
        durationUs = 1_000_000,
        caption = CaptionSpec(words = listOf(Word(text, 0, 1_000_000))),
    )

    private fun project(name: String, language: String = "en") = Project(
        id = "p",
        name = name,
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("src", ClipType.Video, 0, 5_000_000, MediaRef("m", 0, 5_000_000)),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(caption("c1", 0, "Hello"), caption("c2", 2_000_000, "there")),
            ),
        ),
        links = listOf(LinkGroup("g", listOf("src", "c1", "c2"))),
        transcripts = listOf(Transcript("src", language = language)),
    )

    private fun translated(project: Project) =
        TranslateCaptions("pl", mapOf("c1" to "Cześć", "c2" to "wam"), newId).apply(project)

    @Test
    fun theNameSaysTheCaptionsLanguage() {
        val english = project("Projekt bez nazwy")
        val polish = translated(english)

        assertEquals(
            "Projekt bez nazwy 2026-11-07 142005.en.srt",
            SrtFiles.fileName(english, savedAt),
        )
        assertEquals(
            "Projekt bez nazwy 2026-11-07 142005.pl.srt",
            SrtFiles.fileName(polish, savedAt),
        )
        // The language chips choose what is saved, and so its name.
        assertEquals(
            "Projekt bez nazwy 2026-11-07 142005.en.srt",
            SrtFiles.fileName(ShowCaptionLanguage("").apply(polish), savedAt),
        )
        // Whisper heard Polish.
        assertEquals(
            "Lisbon weekend 2026-11-07 142005.pl.srt",
            SrtFiles.fileName(project("Lisbon weekend", language = "pl"), savedAt),
        )
    }

    @Test
    fun captionsWithoutAKnownLanguageAreNamedWithoutOne() {
        // Captions imported from an .srt file: no transcript says what they are in.
        val imported = project("Lisbon weekend").copy(transcripts = emptyList())

        assertEquals(
            "Lisbon weekend 2026-11-07 142005.srt",
            SrtFiles.fileName(imported, savedAt),
        )
        assertEquals(
            "Lisbon weekend 2026-11-07 142005.pl.srt",
            SrtFiles.fileName(translated(imported), savedAt),
        )
    }

    @Test
    fun anotherSaveGetsAnotherName() {
        // The same captions saved again, and two projects that kept the default name.
        val first = SrtFiles.fileName(project("Untitled project"), savedAt)
        val again = SrtFiles.fileName(project("Untitled project"), savedAt.plusSeconds(1))

        assertEquals("Untitled project 2026-11-07 142006.en.srt", again)
        assertNotEquals(first, again)
    }

    @Test
    fun charactersFileSystemsRefuseAreLeftOut() {
        assertEquals(
            "Q1 report  draft 2026-11-07 142005.en.srt",
            SrtFiles.fileName(project("Q1 report: draft"), savedAt),
        )
        assertEquals(
            "a b c 2026-11-07 142005.en.srt",
            SrtFiles.fileName(project("""a/b\c"""), savedAt),
        )
        assertEquals(
            "captions 2026-11-07 142005.en.srt",
            SrtFiles.fileName(project(""" "?*" """), savedAt),
        )
        // The time has no colon, and the language nothing but its code.
        assertEquals(
            "Trip 2026-11-07 142005.pt-BR.srt",
            SrtFiles.fileName(project("Trip", language = "pt-BR/.."), savedAt),
        )
    }
}
