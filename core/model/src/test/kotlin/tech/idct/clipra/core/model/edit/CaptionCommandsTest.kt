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
    fun segmentsBreakAtSentencesPausesAndLength() {
        val long = (0 until 12).map { w("word$it", it * 0.3, it * 0.3 + 0.25) }
        val segments = CaptionSegments.split(long, maxChars = 30)
        assertTrue(segments.all { s -> s.joinToString(" ") { it.text }.length <= 30 })
        assertEquals(12, segments.sumOf { it.size })
    }
}
