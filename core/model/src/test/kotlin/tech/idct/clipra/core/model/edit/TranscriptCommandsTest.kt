package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.CaptionOptions
import tech.idct.clipra.core.model.Fillers
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word

/** Storing transcripts and recognizing filler words (#44). */
class TranscriptCommandsTest {
    @Test
    fun newTranscriptsReplaceTheirClipsOnly() {
        val old = Transcript("a", "en", listOf(Word("old", 0, 100_000)))
        val other = Transcript("b", "en", listOf(Word("kept", 0, 100_000)))
        val project = Project(id = "p", name = "p", transcripts = listOf(old, other))
        val fresh = Transcript("a", "pl", listOf(Word("nowe", 0, 200_000)))
        val options = CaptionOptions(highlightWords = false, removeFillers = true)
        val edited = SetTranscripts(listOf(fresh), options).apply(project)
        assertEquals(setOf(fresh, other), edited.transcripts.toSet())
        assertEquals(options, edited.captionOptions)
    }

    @Test
    fun fillersAreRecognizedPerLanguage() {
        assertTrue(Fillers.isFiller("Um,", "en"))
        assertTrue(Fillers.isFiller("erm", "en"))
        assertTrue(Fillers.isFiller("Yyy...", "pl"))
        assertTrue(Fillers.isFiller("hmm", "pl"))
        assertFalse(Fillers.isFiller("yyy", "en"))
        assertFalse(Fillers.isFiller("umbrella", "en"))
        assertFalse(Fillers.isFiller("...", "en"))
    }
}
