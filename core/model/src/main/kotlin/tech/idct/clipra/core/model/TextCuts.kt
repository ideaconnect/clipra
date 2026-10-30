package tech.idct.clipra.core.model

import kotlin.math.roundToLong

/** Edit by text (#48): what a transcript shows, as design/canvas/Transcript.dc.html has it. */
enum class TokenKind { Word, Filler, Pause }

/** A word, a filler or a pause, from [startUs] to [endUs] on the timeline. */
data class Token(
    val id: String,
    val kind: TokenKind,
    val text: String,
    val startUs: Long,
    val endUs: Long,
) {
    val durationUs: Long get() = endUs - startUs
}

/** The tokens of one source clip, in time order. */
data class TranscriptSource(val clip: Clip, val tokens: List<Token>)

/**
 * Which tokens are cut: fillers and pauses over [thresholdUs] when their switches are on,
 * unless [restored]; anything else that was tapped ([cut]).
 */
data class CutChoices(
    val fillers: Boolean = true,
    val pauses: Boolean = true,
    val thresholdUs: Long = DEFAULT_THRESHOLD_US,
    val restored: Set<String> = emptySet(),
    val cut: Set<String> = emptySet(),
) {
    /** Whether the switches cut [token] on their own. */
    fun isAuto(token: Token): Boolean = (token.kind == TokenKind.Filler && fillers) ||
        (token.kind == TokenKind.Pause && pauses && token.durationUs > thresholdUs)

    fun isCut(token: Token): Boolean = if (isAuto(token)) token.id !in restored else token.id in cut

    /** Taps [token]: cuts it, or restores it. */
    fun toggle(token: Token): CutChoices = if (isAuto(token)) {
        copy(restored = restored xor token.id)
    } else {
        copy(cut = cut xor token.id)
    }

    private infix fun Set<String>.xor(id: String) = if (id in this) this - id else this + id

    companion object {
        const val DEFAULT_THRESHOLD_US = 500_000L
        const val MIN_THRESHOLD_US = 300_000L
        const val MAX_THRESHOLD_US = 2_000_000L
    }
}

/** What the cuts add up to (the Length card and the switches' notes). */
data class CutSummary(val cuts: Int, val cutUs: Long, val fillers: Int, val longPauses: Int)

/** Builds the tokens, totals the cuts and turns them into timeline ranges. */
object TextCuts {
    /** Gaps shorter than this are not shown as pauses: the slider starts here. */
    const val MIN_PAUSE_US = CutChoices.MIN_THRESHOLD_US

    /**
     * The transcribed clips of [project] as tokens on the timeline: their words (fillers
     * marked) where the clip plays them, and the pauses between words.
     */
    fun sources(project: Project): List<TranscriptSource> =
        project.transcripts.mapNotNull { source(project, it) }.sortedBy { it.clip.startUs }

    private fun source(project: Project, transcript: Transcript): TranscriptSource? {
        val clip = project.findClip(transcript.clipId)?.second ?: return null
        val words = transcript.words.mapNotNull { word -> timeline(clip, word)?.let { word to it } }
        val tokens = mutableListOf<Token>()
        words.forEachIndexed { i, (word, range) ->
            val previous = words.getOrNull(i - 1)?.second
            if (previous != null && range.first - previous.last >= MIN_PAUSE_US) {
                tokens += Token("${clip.id}:p$i", TokenKind.Pause, "", previous.last, range.first)
            }
            val kind = if (word.kind == WordKind.Filler) TokenKind.Filler else TokenKind.Word
            tokens += Token("${clip.id}:w$i", kind, word.text, range.first, range.last)
        }
        return TranscriptSource(clip, tokens).takeIf { tokens.isNotEmpty() }
    }

    fun summary(sources: List<TranscriptSource>, choices: CutChoices): CutSummary {
        val tokens = sources.flatMap { it.tokens }
        val cut = tokens.filter(choices::isCut)
        return CutSummary(
            cuts = cut.size,
            cutUs = cut.sumOf { it.durationUs },
            fillers = tokens.count { it.kind == TokenKind.Filler },
            longPauses = tokens.count {
                it.kind == TokenKind.Pause && it.durationUs > choices.thresholdUs
            },
        )
    }

    /**
     * The cut stretches of the timeline in time order. Tokens cut one after another make one
     * stretch, with the short gaps between them (too short to be pauses), so no slivers stay.
     */
    fun ranges(sources: List<TranscriptSource>, choices: CutChoices): List<LongRange> {
        val runs = mutableListOf<LongRange>()
        for (source in sources) {
            var run: LongRange? = null
            for (token in source.tokens) {
                run = if (choices.isCut(token)) {
                    run?.let { it.first..token.endUs } ?: (token.startUs..token.endUs)
                } else {
                    run?.let(runs::add)
                    null
                }
            }
            run?.let(runs::add)
        }
        val ranges = mutableListOf<LongRange>()
        for (range in runs.sortedBy { it.first }) {
            val last = ranges.lastOrNull()
            if (last != null && range.first <= last.last) {
                ranges[ranges.lastIndex] = last.first..maxOf(last.last, range.last)
            } else {
                ranges += range
            }
        }
        return ranges
    }

    /** [word]'s stretch of the timeline in [clip], or null when the clip doesn't play it. */
    private fun timeline(clip: Clip, word: Word): LongRange? {
        val ref = clip.source ?: return null
        val rate = clip.speed.rate.takeIf { it > 0 } ?: return null
        val start = clip.startUs + ((word.startUs - ref.inPointUs) / rate).roundToLong()
        val end = clip.startUs + ((word.endUs - ref.inPointUs) / rate).roundToLong()
        if (end <= clip.startUs || start >= clip.endUs) return null
        return start.coerceAtLeast(clip.startUs)..end.coerceAtMost(clip.endUs)
    }
}
