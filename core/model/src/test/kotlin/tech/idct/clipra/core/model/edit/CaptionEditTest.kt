package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.CaptionText
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.LinkGroup
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Srt
import tech.idct.clipra.core.model.Word

/** Caption editing (#46): words, split and merge, search and replace, and .srt. */
class CaptionEditTest {
    private val sec = 1_000_000L

    private fun ids(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    /** Words a second apart from [at] (clip time), each 0.8 s long. */
    private fun words(text: String, at: Long = 0, confidence: Float = 0.9f) =
        text.split(" ").mapIndexed { i, t ->
            Word(t, at + i * sec, at + i * sec + 800_000, confidence)
        }

    private fun caption(id: String, start: Long, text: String) = Clip(
        id = id,
        type = ClipType.Caption,
        startUs = start,
        durationUs = text.split(" ").size * sec,
        caption = CaptionSpec(
            words = words(text),
            style = CaptionStyle.Boxed,
            position = CaptionPosition.Top,
        ),
    )

    // L1: the video the captions came from. L2: two captions linked to it.
    private val project = Project(
        id = "p",
        name = "Lisbon",
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("v", ClipType.Video, 0, 20 * sec, source = MediaRef("m", 0, 20 * sec)),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    caption("c1", 6 * sec, "Tomorrow we take the tram up to Alfama"),
                    caption("c2", 15 * sec, "and then the train"),
                ),
            ),
        ),
        links = listOf(LinkGroup("g", listOf("v", "c1", "c2"))),
    )

    private fun Project.caption(id: String) = findClip(id)!!.second.caption!!

    private fun Project.text(id: String) = caption(id).text

    private fun applyAndCheckInverse(command: EditCommand, from: Project = project): Project {
        val history = EditHistory(from)
        val edited = history.apply(command)
        assertEquals(from, history.undo())
        assertEquals(edited, history.redo())
        return edited
    }

    @Test
    fun aRetypedWordKeepsItsTimeAndIsChecked() {
        val retyped = CaptionText.retype(words("up to Alfama"), 2, "Alfama!")
        assertEquals(Word("Alfama!", 2 * sec, 2_800_000, 1f), retyped[2])
        assertEquals(words("up to Alfama").take(2), retyped.take(2))

        // Two words share the time by length; nothing takes the word out.
        val two = CaptionText.retype(words("tram"), 0, "cable car")
        assertEquals(listOf("cable", "car"), two.map { it.text })
        assertEquals(0L, two[0].startUs)
        assertEquals(two[0].endUs, two[1].startUs)
        assertEquals(800_000L, two[1].endUs)
        assertTrue(CaptionText.retype(words("tram"), 0, "  ").isEmpty())
    }

    @Test
    fun replaceFindsPartsOfWordsAndRunsOverSeveral() {
        val line = words("Tomorrow we take the tram up to Alfama")
        assertNull(CaptionText.replace(line, "Sintra", "x"))
        assertEquals(listOf(4..4), CaptionText.matches(line, "TRAM"))
        assertEquals(listOf(4..6), CaptionText.matches(line, "tram up to"))

        val car = CaptionText.replace(line, "the tram", "a cable car")!!
        assertEquals("Tomorrow we take a cable car up to Alfama", car.joinToString(" ") { it.text })
        // The new words fill the time of the words they replace.
        assertEquals(3 * sec, car[3].startUs)
        assertEquals(4_800_000L, car[5].endUs)
        assertEquals(line[5], car[6])

        // Part of a word, and more than once in it.
        val banana = CaptionText.replace(words("banana split"), "an", "AN")!!
        assertEquals("bANANa split", banana.joinToString(" ") { it.text })
    }

    @Test
    fun editingAWordChecksItAndAnEmptyCaptionGoes() {
        val edited = applyAndCheckInverse(EditCaptionWord("c1", 7, "Alfama."))
        assertEquals("Tomorrow we take the tram up to Alfama.", edited.text("c1"))
        assertEquals(1f, edited.caption("c1").words[7].confidence)
        // The look stays.
        assertEquals(CaptionStyle.Boxed, edited.caption("c1").style)

        var gone = project
        repeat(4) { gone = EditCaptionWord("c2", 0, "").apply(gone) }
        assertNull(gone.findClip("c2"))
        assertEquals(listOf("v", "c1"), gone.links.single().clipIds)
    }

    @Test
    fun aSplitCaptionStartsAtItsWordAndStaysLinked() {
        val split = applyAndCheckInverse(SplitCaption("c1", 5, ids("c1b")))
        assertEquals("Tomorrow we take the tram", split.text("c1"))
        assertEquals("up to Alfama", split.text("c1b"))
        val right = split.findClip("c1b")!!.second
        assertEquals(11 * sec, right.startUs)
        assertEquals(3 * sec, right.durationUs)
        assertEquals(0L, right.caption!!.words.first().startUs)
        assertEquals(5 * sec, split.findClip("c1")!!.second.durationUs)
        assertTrue("c1b" in split.linkedPartners("v"))

        assertThrows(EditRejected::class.java) { SplitCaption("c1", 0).apply(project) }
        assertThrows(EditRejected::class.java) { SplitCaption("c2", 4).apply(project) }
    }

    @Test
    fun mergingJoinsTheNextCaptionWithItsTimes() {
        val merged = applyAndCheckInverse(MergeCaptions("c1"))
        assertNull(merged.findClip("c2"))
        val clip = merged.findClip("c1")!!.second
        assertEquals(6 * sec, clip.startUs)
        assertEquals(13 * sec, clip.durationUs)
        assertEquals(
            "Tomorrow we take the tram up to Alfama and then the train",
            clip.caption!!.text,
        )
        // "and" was at 15 s on the timeline: 9 s into the merged caption.
        assertEquals(9 * sec, clip.caption!!.words[8].startUs)
        assertThrows(EditRejected::class.java) { MergeCaptions("c2").apply(project) }
    }

    @Test
    fun replaceAllIsOneStepOverEveryCaption() {
        val replaced = applyAndCheckInverse(ReplaceInCaptions("the", "THE"))
        assertEquals("Tomorrow we take THE tram up to Alfama", replaced.text("c1"))
        assertEquals("and THEn THE train", replaced.text("c2"))
        // Nothing found, nothing changes.
        assertEquals(project, ReplaceInCaptions("Sintra", "x").apply(project))
    }

    @Test
    fun srtRoundTripsTheCaptions() {
        val srt = Srt.write(Srt.cues(project))
        assertEquals(
            "1\n00:00:06,000 --> 00:00:14,000\nTomorrow we take the tram up to Alfama\n\n" +
                "2\n00:00:15,000 --> 00:00:19,000\nand then the train\n\n",
            srt,
        )
        assertEquals(Srt.cues(project), Srt.read(srt))
    }

    @Test
    fun srtReadsWhatPlayersAccept() {
        val text = "﻿1\r\n00:00:01.5 --> 00:00:03,250\r\n<i>Hello</i>\r\nthere\r\n" +
            "2\r\n00:00:04,000 --> 00:00:05,000\r\n{\\an8}Up top\r\n\r\n" +
            "garbage\r\n\r\n3\r\n00:00:09,000 --> 00:00:08,000\r\nBackwards\r\n"
        assertEquals(
            listOf(
                Srt.Cue(1_500_000, 3_250_000, "Hello there"),
                Srt.Cue(4 * sec, 5 * sec, "Up top"),
            ),
            Srt.read(text),
        )
    }

    @Test
    fun importedCaptionsReplaceTheOldOnesWithTheirLook() {
        val cues = listOf(
            Srt.Cue(1 * sec, 4 * sec, "We finally made it"),
            // Overlaps the next: it ends where that one starts.
            Srt.Cue(5 * sec, 9 * sec, "to Lisbon"),
            Srt.Cue(8 * sec, 10 * sec, "today"),
        )
        val imported = applyAndCheckInverse(ImportCaptions(cues, ids("a", "b", "c", "L9")))
        assertNull(imported.findClip("c1"))
        val layer = imported.layers.single { it.id == "L2" }
        assertEquals(listOf("a", "b", "c"), layer.clips.map { it.id })
        assertEquals(3 * sec, layer.clips[1].durationUs)
        val first = layer.clips[0].caption!!
        assertEquals(CaptionStyle.Boxed, first.style)
        assertEquals(CaptionPosition.Top, first.position)
        assertEquals(listOf("We", "finally", "made", "it"), first.words.map { it.text })
        assertEquals(3 * sec, first.words.last().endUs)
        assertEquals(Srt.cues(imported).map { it.text }, cues.map { it.text })

        // With no captions yet, they go on a new layer at the top.
        val bare = project.copy(layers = project.layers.take(1), links = emptyList())
        val fresh = ImportCaptions(cues, ids("a", "b", "c", "L9")).apply(bare)
        assertEquals("L9", fresh.layers.last().id)
        assertThrows(EditRejected::class.java) { ImportCaptions(emptyList()).apply(project) }
    }
}
