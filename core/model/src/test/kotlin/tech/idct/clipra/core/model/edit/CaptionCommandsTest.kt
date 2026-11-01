package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.CaptionOptions
import tech.idct.clipra.core.model.CaptionPosition
import tech.idct.clipra.core.model.CaptionSegments
import tech.idct.clipra.core.model.CaptionStyle
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.WordKind

/** Caption clips from transcripts (#45): timing, splitting, layers, links and restyling. */
class CaptionCommandsTest {
    private var ids = 0
    private val newId = { "id${ids++}" }

    private fun w(text: String, start: Double, end: Double, kind: WordKind = WordKind.Word) =
        Word(text, (start * 1e6).toLong(), (end * 1e6).toLong(), kind = kind)

    // A clip on the timeline at 10 s that plays its media from 2 s to 12 s.
    private val source = Clip(
        id = "src",
        type = ClipType.Video,
        startUs = 10_000_000,
        durationUs = 10_000_000,
        source = MediaRef("m", 2_000_000, 12_000_000),
    )

    private val words = listOf(
        w("Before", 1.0, 1.5), // before the clip's in-point: not played
        w("We", 2.0, 2.2), w("finally", 2.2, 2.6), w("made", 2.6, 2.9), w("it", 2.9, 3.0),
        w("um", 3.0, 3.3, WordKind.Filler), w("to", 3.3, 3.4), w("Lisbon.", 3.4, 4.0),
        w("The", 5.0, 5.2), w("light", 5.2, 5.5), w("here", 5.5, 5.8), w("is", 5.8, 5.9),
        w("unreal", 5.9, 6.5),
    )

    private fun project(options: CaptionOptions = CaptionOptions()) = Project(
        id = "p",
        name = "p",
        format = Format(),
        layers = listOf(Layer("L1", clips = listOf(source))),
        transcripts = listOf(Transcript("src", "en", words)),
        captionOptions = options,
    )

    @Test
    fun wordsBecomeCaptionsOnANewTopLayerLinkedToTheirSource() {
        val edited = CreateCaptions(listOf("src"), CaptionStyle.Karaoke, newId).apply(project())
        assertEquals(2, edited.layers.size)
        val captions = edited.layers.last().clips
        assertEquals(
            listOf("We finally made it um to Lisbon.", "The light here is unreal"),
            captions.map { it.caption!!.text },
        )
        // "We" is at 2 s of media, which the clip plays at 10 s on the timeline.
        assertEquals(10_000_000L, captions[0].startUs)
        assertEquals(0L, captions[0].caption!!.words[0].startUs)
        assertEquals(13_000_000L, captions[1].startUs)
        assertTrue(captions.all { it.caption!!.style == CaptionStyle.Karaoke })
        assertEquals(setOf("src") + captions.map { it.id }, edited.links.single().clipIds.toSet())
    }

    @Test
    fun fillersAreLeftOutWhenAskedAndAgainRunsReplaceTheOldCaptions() {
        val first = CreateCaptions(listOf("src"), newId = newId)
            .apply(project(CaptionOptions(removeFillers = true)))
        assertEquals("We finally made it to Lisbon.", first.layers.last().clips[0].caption!!.text)
        val again = CreateCaptions(listOf("src"), newId = newId)
            .apply(first.copy(captionOptions = first.captionOptions.copy(newLayer = false)))
        assertEquals(2, again.layers.size)
        assertEquals(2, again.layers.last().clips.size)
    }

    @Test
    fun aDeletedCaptionGoesAloneAndTheSourceTakesItsCaptions() {
        val edited = CreateCaptions(listOf("src"), newId = newId).apply(project())
        val (first, second) = edited.layers.last().clips
        for (ripple in listOf(true, false)) {
            val history = EditHistory(edited)
            val deleted = history.apply(DeleteClip(first.id, ripple))
            // The source and the caption after it stay where they were, with their link.
            assertEquals(listOf(second), deleted.layers.last().clips)
            assertEquals(edited.layers.first(), deleted.layers.first())
            assertEquals(setOf("src", second.id), deleted.links.single().clipIds.toSet())
            assertEquals(edited, history.undo())
        }

        val withoutSource = DeleteClip("src").apply(edited)
        assertTrue(withoutSource.layers.all { it.clips.isEmpty() })
        assertTrue(withoutSource.links.isEmpty())
    }

    @Test
    fun captionsMoveAndSplitWithTheirSource() {
        val edited = CreateCaptions(listOf("src"), newId = newId).apply(project())
        val moved = MoveClip("src", 12_000_000).apply(edited)
        assertEquals(12_000_000L, moved.layers.last().clips[0].startUs)

        // Splitting at 11 s cuts the first caption between "finally" and "made".
        val split = SplitClip("src", 11_000_000 - 400_000, newId).apply(edited)
        val parts = split.layers.last().clips.sortedBy { it.startUs }
        assertEquals("We finally", parts[0].caption!!.text)
        assertEquals("made it um to Lisbon.", parts[1].caption!!.text)
        assertEquals(0L, parts[1].caption!!.words[0].startUs - 0L)
        // The right part of the source keeps the transcript.
        assertEquals(2, split.transcripts.size)
    }

    @Test
    fun aLookAppliesToEveryCaption() {
        val edited = CreateCaptions(listOf("src"), newId = newId).apply(project())
        val restyled = SetCaptionLook(
            style = CaptionStyle.Boxed,
            position = CaptionPosition.Top,
            scale = 3f,
        ).apply(edited)
        val captions = restyled.layers.last().clips.map { it.caption!! }
        assertTrue(
            captions.all {
                it.style == CaptionStyle.Boxed &&
                    it.position == CaptionPosition.Top
            },
        )
        assertTrue(captions.all { it.scale == SetCaptionLook.MAX_SCALE })
    }

    @Test
    fun soundsWhisperNotesAreNotCaptioned() {
        val heard = listOf(
            w("[MUZYKA]", 0.0, 5.5), w("Witam", 6.0, 6.3), w("(śmiech)", 6.3, 6.8),
            w("Was", 6.8, 7.0), w("[Muzyka", 7.0, 7.2), w("gra]", 7.2, 7.6), w("♪", 7.6, 7.8),
            w("na", 7.8, 7.9), w("*music*", 7.9, 8.0), w("kanale.", 8.0, 8.5),
        )
        assertEquals(
            listOf("Witam", "Was", "na", "kanale."),
            CaptionSegments.withoutSoundTags(heard).map { it.text },
        )
    }

    @Test
    fun aSentencesLastWordJoinsItsCaptionInsteadOfFlashingPast() {
        // As Whisper heard a Polish talk: "tech." didn't fit its line and was left alone for
        // 0.7 s, too short to read.
        val heard = listOf(
            w("trafia", 0.0, 0.3), w("na", 0.3, 0.4), w("do", 0.4, 0.5), w("sekcji", 0.5, 0.9),
            w("tips", 0.9, 1.2), w("a", 1.2, 1.3), w("nie", 1.3, 1.5), w("tech.", 1.5, 2.0),
            w("Bo", 2.2, 2.3), w("w", 2.3, 2.4), w("sekcji", 2.4, 2.8), w("tech", 2.8, 3.1),
            w("będziemy", 4.0, 4.5),
        )
        val split = CaptionSegments.split(heard, maxChars = 34)
        assertEquals(listOf("tech."), split[1].map { it.text })
        val lines = CaptionSegments.readable(split, maxChars = 34, endUs = 10_000_000)
        assertEquals(
            listOf("trafia na do sekcji tips a nie tech.", "Bo w sekcji tech", "będziemy"),
            lines.map { line -> line.joinToString(" ") { it.text } },
        )
        val ends = CaptionSegments.shownUntil(lines, endUs = 10_000_000)
        // Each caption gives way to the next; a gap too short to read as a pause is closed.
        assertEquals(listOf(2_200_000L, 4_000_000L), ends.take(2))
        // The last one holds half a second after its word, a second in all.
        assertEquals(5_000_000L, ends[2])
        // But never past the end of the clip that speaks it.
        assertEquals(4_700_000L, CaptionSegments.shownUntil(lines, endUs = 4_700_000)[2])
    }

    @Test
    fun captionsStayLongEnoughToRead() {
        val edited = CreateCaptions(listOf("src"), newId = newId).apply(project())
        val captions = edited.layers.last().clips
        // "Lisbon." ends at 12 s on the timeline; held half a second, the caption would leave
        // a gap of half a second before the next one, which only flickers, so it stays on.
        assertEquals(captions[1].startUs, captions[0].endUs)
        // "unreal" ends at 14.5 s; the last caption holds half a second more.
        assertEquals(15_000_000L, captions[1].endUs)
        assertTrue(captions.all { it.durationUs >= CaptionSegments.MIN_SHOWN_US })
    }

    @Test
    fun segmentsBreakAtSentencesPausesAndLength() {
        val long = (0 until 12).map { w("word$it", it * 0.3, it * 0.3 + 0.25) }
        val segments = CaptionSegments.split(long, maxChars = 30)
        assertTrue(segments.all { s -> s.joinToString(" ") { it.text }.length <= 30 })
        assertEquals(12, segments.sumOf { it.size })
    }
}
