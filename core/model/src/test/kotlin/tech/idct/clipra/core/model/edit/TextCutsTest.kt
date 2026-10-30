package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.CaptionOptions
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.CutChoices
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextCuts
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TokenKind
import tech.idct.clipra.core.model.Transcript
import tech.idct.clipra.core.model.Word
import tech.idct.clipra.core.model.WordKind
import kotlin.math.abs
import kotlin.math.roundToLong

/** Edit by text (#48): the design's numbers, and cuts that keep everything in sync. */
class TextCutsTest {
    private fun w(text: String, at: Double, d: Double, filler: Boolean = false) = Word(
        text,
        (at * 1e6).roundToLong(),
        ((at + d) * 1e6).roundToLong(),
        kind = if (filler) WordKind.Filler else WordKind.Word,
    )

    // The two transcripts of design/canvas/Transcript.dc.html, in timeline time.
    private val first = listOf(
        w("um", 5.5, 0.4, true), w("We", 6.0, 0.4), w("finally", 6.4, 0.5), w("made", 6.9, 0.4),
        w("it", 7.3, 0.3), w("to", 7.6, 0.3), w("Lisbon.", 7.9, 0.9), w("The", 9.4, 0.3),
        w("light", 9.7, 0.4), w("here", 10.1, 0.3), w("is,", 10.4, 0.2), w("uh,", 10.6, 0.3, true),
        w("unreal.", 10.9, 0.7),
    )
    private val second = listOf(
        w(
            "Tomorrow",
            12.1,
            0.5,
        ),
        w("we’re", 12.6, 0.3), w("taking", 12.9, 0.4), w("the", 13.3, 0.2),
        w("tram", 13.5, 0.4), w("up", 13.9, 0.3), w("to", 14.2, 0.2), w("Alfama,", 14.4, 0.6),
        w("and", 15.6, 0.2), w("then", 15.8, 0.4), w("um", 16.2, 0.4, true), w("the", 16.6, 0.2),
        w("train", 16.8, 0.4), w("out", 17.2, 0.3), w("to", 17.5, 0.2), w("Sintra,", 17.7, 0.5),
        w("if", 18.9, 0.2), w("the", 19.1, 0.2), w("weather", 19.3, 0.5), w("holds,", 19.8, 0.6),
        w("you know.", 20.6, 0.4, true),
    )

    private val sunset = Clip(
        id = "sunset",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 12_000_000,
        source = MediaRef("v", 0, 12_000_000),
    )
    private val voiceover = Clip(
        id = "voice",
        type = ClipType.Audio,
        startUs = 12_000_000,
        durationUs = 30_000_000,
        source = MediaRef("a", 12_000_000, 42_000_000),
    )
    private val music = Clip(
        id = "music",
        type = ClipType.Audio,
        startUs = 0,
        durationUs = 42_000_000,
        source = MediaRef("m", 0, 42_000_000),
    )
    private val title = Clip(
        id = "title",
        type = ClipType.Text,
        startUs = 16_000_000,
        durationUs = 2_000_000,
        text = TextSpec("Sintra"),
    )

    private val project = Project(
        id = "p",
        name = "p",
        layers = listOf(
            Layer("L1", clips = listOf(music)),
            Layer("L2", clips = listOf(sunset)),
            Layer("L3", clips = listOf(voiceover)),
            Layer("L4", clips = listOf(title)),
        ),
        transcripts = listOf(Transcript("sunset", "en", first), Transcript("voice", "en", second)),
        captionOptions = CaptionOptions(newLayer = true),
    )

    @Test
    fun theDesignsNumbers() {
        val sources = TextCuts.sources(project)
        assertEquals(listOf("sunset", "voice"), sources.map { it.clip.id })
        val choices = CutChoices()
        val summary = TextCuts.summary(sources, choices)
        assertEquals(4, summary.fillers)
        assertEquals(3, summary.longPauses)
        assertEquals(7, summary.cuts)
        assertEquals(3_400_000L, summary.cutUs)
        // 0:42.0 becomes 0:38.6.
        assertEquals(38_600_000L, 42_000_000L - summary.cutUs)

        val stricter = choices.copy(thresholdUs = 600_000)
        assertEquals(1, TextCuts.summary(sources, stricter).longPauses)
        assertEquals(5, TextCuts.summary(sources, stricter).cuts)

        val tokens = sources.flatMap { it.tokens }
        val we = tokens.first { it.text == "We" }
        val um = tokens.first { it.kind == TokenKind.Filler }
        val tapped = choices.toggle(we).toggle(um)
        assertEquals(7, TextCuts.summary(sources, tapped).cuts)
        assertTrue(tapped.isCut(we))
        assertTrue(!tapped.isCut(um))
        // A restored filler stays uncut, and the tapped word cut, once the switches are off.
        val off = tapped.copy(fillers = false, pauses = false)
        assertEquals(1, TextCuts.summary(sources, off).cuts)
        assertEquals(0, TextCuts.summary(sources, CutChoices(fillers = false, pauses = false)).cuts)
    }

    @Test
    fun afterCuttingCaptionsMusicAndOverlaysStayInSync() {
        val captioned = CreateCaptions(listOf("sunset", "voice")).apply(project)
        val sources = TextCuts.sources(captioned)
        val ranges = TextCuts.ranges(sources, CutChoices())
        val removed = ranges.sumOf { it.last - it.first }
        assertEquals(3_400_000L, removed)
        val cut = CutTimeRanges(ranges).apply(captioned)

        // The length, and the overlay moved up by what was cut before it.
        assertEquals(42_000_000L - removed, cut.durationUs)
        val before = ranges.filter { it.last <= 16_000_000 }.sumOf { it.last - it.first }
        assertEquals(16_000_000L - before, cut.findClip("title")!!.second.startUs)

        // Every caption word shows when its source clip plays it.
        val captions = cut.layers.last().clips
        assertTrue(captions.isNotEmpty())
        for (caption in captions) {
            for (word in caption.caption!!.words) {
                val shown = caption.startUs + word.startUs
                val played = playedAt(cut, word.text, shown)
                assertTrue(
                    "${word.text} shown at $shown, played at $played",
                    abs(shown - played) <= 1,
                )
            }
        }
        // The music was cut with everything else and still covers the whole video.
        val musicParts = cut.layers[0].clips
        assertEquals(cut.durationUs, musicParts.sumOf { it.durationUs })
        assertTrue(musicParts.zipWithNext().all { (a, b) -> a.endUs == b.startUs })
        // Cut words are gone from the edit-by-text view; the rest remain.
        val after = TextCuts.sources(cut).flatMap { it.tokens }
        assertTrue(after.none { it.kind == TokenKind.Filler })
        assertEquals(30, after.count { it.kind == TokenKind.Word })
    }

    @Test
    fun wordsCutTogetherLeaveNoSliversAndTheirCaptionGoes() {
        val captioned = CreateCaptions(listOf("sunset")).apply(project)
        val sources = TextCuts.sources(captioned)
        val tokens = sources.first().tokens
        // "We finally made it to Lisbon." has 0.1 s gaps in it, too short to be pauses.
        var choices = CutChoices(fillers = false, pauses = false)
        for (text in listOf("We", "finally", "made", "it", "to", "Lisbon.")) {
            choices = choices.toggle(tokens.first { it.text == text })
        }
        val ranges = TextCuts.ranges(sources, choices)
        assertEquals(listOf(6_000_000L..8_800_000L), ranges)
        val cut = CutTimeRanges(ranges).apply(captioned)
        val captions = cut.layers.last().clips.map { it.caption!!.text }
        assertTrue(captions.none { it.isEmpty() || "Lisbon" in it })
        // The source is split around the cut, nothing more.
        assertEquals(2, cut.layers[1].clips.size)
    }

    /** Where the source clip that has [text] in its transcript plays it, nearest [near]. */
    private fun playedAt(project: Project, text: String, near: Long): Long =
        project.transcripts.flatMap { transcript ->
            val clip = project.findClip(transcript.clipId)!!.second
            val ref = clip.source!!
            transcript.words.filter { it.text == text }.map { word ->
                clip.startUs + (word.startUs - ref.inPointUs)
            }.filter { it in clip.startUs until clip.endUs }
        }.minBy { abs(it - near) }
}
