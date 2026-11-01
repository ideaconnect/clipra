package tech.idct.clipra.core.model

import kotlinx.serialization.Serializable

/** How captions look (#45, CaptionsEdit board). */
@Serializable
enum class CaptionStyle { Clean, BoldPop, Boxed, Karaoke, Minimal }

/** Where captions sit in the frame. */
@Serializable
enum class CaptionPosition { Top, Middle, Bottom }

/**
 * A caption clip (#45): its [words], timed from the clip's start, so the caption moves with its
 * clip; how it looks; where it sits; and its [scale] on the style's own size. [highlight] marks
 * the word being spoken; [maxTwoLines] shrinks long captions to fit two lines. A translation
 * (#47) has its [language] (ISO 639-1); captions as heard have none.
 */
@Serializable
data class CaptionSpec(
    val words: List<Word>,
    val style: CaptionStyle = CaptionStyle.BoldPop,
    val position: CaptionPosition = CaptionPosition.Bottom,
    val scale: Float = 1f,
    val highlight: Boolean = true,
    val maxTwoLines: Boolean = true,
    val language: String = "",
) {
    val text: String get() = words.joinToString(" ") { it.text }
}

/**
 * The caption styles of design/canvas/CaptionsEdit.dc.html, as the renderer draws them. Sizes
 * are fractions of the frame's short side; [basePt] is what the Size slider shows at scale 1.
 * Colours are ARGB.
 */
object CaptionStyles {
    data class Look(
        val font: String,
        val weight: Int,
        val uppercase: Boolean,
        val size: Float,
        val basePt: Int,
        /** A dark box behind each line (Boxed). */
        val box: Boolean = false,
        /** Shadow blur and offset, in fractions of the font size; 0 for none. */
        val shadowBlur: Float,
        val shadowY: Float,
        val shadowAlpha: Float,
    )

    const val WHITE = 0xFFFFFFFF
    const val ACCENT = 0xFFFFD23F
    const val ON_ACCENT = 0xFF141210
    const val BOX = 0xB8000000

    /** Words after the spoken one in Karaoke. */
    const val UPCOMING_ALPHA = 0.5f

    fun look(style: CaptionStyle): Look = when (style) {
        CaptionStyle.Clean -> Look(
            font = TextFonts.GEIST,
            weight = 700,
            uppercase = false,
            size = 0.072f,
            basePt = 18,
            shadowBlur = 0.33f,
            shadowY = 0.08f,
            shadowAlpha = 0.85f,
        )

        CaptionStyle.BoldPop -> Look(
            font = TextFonts.BRICOLAGE,
            weight = 800,
            uppercase = true,
            size = 0.09f,
            basePt = 22,
            shadowBlur = 0.67f,
            shadowY = 0.13f,
            shadowAlpha = 0.6f,
        )

        CaptionStyle.Boxed -> Look(
            font = TextFonts.GEIST, weight = 600, uppercase = false, size = 0.072f, basePt = 18,
            box = true, shadowBlur = 0f, shadowY = 0f, shadowAlpha = 0f,
        )

        CaptionStyle.Karaoke -> Look(
            font = TextFonts.GEIST,
            weight = 800,
            uppercase = false,
            size = 0.078f,
            basePt = 20,
            shadowBlur = 0.46f,
            shadowY = 0.08f,
            shadowAlpha = 0.9f,
        )

        CaptionStyle.Minimal -> Look(
            font = TextFonts.GEIST,
            weight = 500,
            uppercase = false,
            size = 0.065f,
            basePt = 16,
            shadowBlur = 0.27f,
            shadowY = 0.09f,
            shadowAlpha = 0.9f,
        )
    }
}

/**
 * Splits timed words into captions (#45): a new caption after a sentence ends, after a pause of
 * [PAUSE_US] or more, when the text would pass [maxChars] (about two lines), or after
 * [MAX_DURATION_US]. Then makes every caption readable ([readable], [shownUntil]).
 */
object CaptionSegments {
    const val PAUSE_US = 600_000L
    const val MAX_DURATION_US = 5_000_000L

    /** How long a caption stays after its last word, so its end can be read. */
    const val HOLD_US = 500_000L

    /** The least a caption stays on screen; broadcast specs ask for about a second. */
    const val MIN_SHOWN_US = 1_000_000L

    /** A gap shorter than this between two captions only flickers, so it is closed. */
    const val MIN_GAP_US = 800_000L

    /** Below this a caption flashes past, so it may grow longer to be joined. */
    const val FLASH_US = 800_000L

    /**
     * The fastest a caption is expected to be read, in characters a second. Faster than
     * subtitles, since the viewer hears the same words and the highlight keeps their place.
     */
    const val READING_CPS = 25

    /**
     * Whisper's notes on sounds that aren't speech, such as "[MUZYKA]", "(laughs)" or "♪":
     * they are not words anyone says, so captions leave them out.
     */
    fun withoutSoundTags(words: List<Word>): List<Word> {
        val kept = ArrayList<Word>(words.size)
        var inside: Char? = null
        for (word in words) {
            val text = word.text.trim()
            val close = inside
            if (close != null) {
                if (text.endsWith(close)) inside = null
                continue
            }
            if (text.isNotEmpty() && text.all { it in MUSIC || it.isWhitespace() }) continue
            val open = text.firstOrNull()?.let { TAGS[it] }
            if (open != null) {
                if (!text.endsWith(open)) inside = open
                continue
            }
            kept += word
        }
        return kept
    }

    /**
     * Joins captions too short to read into a neighbour, as Selfroller does. A caption can only
     * stay from its first word until the next one starts; when that is less than it needs
     * ([MIN_SHOWN_US], or its text at [READING_CPS]), it shares a neighbour's time instead. The
     * worst first; between two neighbours, the one on this side of a pause, then the one before,
     * since a caption that short is usually the end of a sentence. Joined captions stay within
     * one and a half times [maxChars] (twice, to save one that would flash past), so they still
     * fit about two lines. Every word keeps its time, so the highlight stays in sync.
     */
    fun readable(segments: List<List<Word>>, maxChars: Int, endUs: Long): List<List<Word>> {
        val lines = segments.filter { it.isNotEmpty() }.toMutableList()
        while (true) {
            val ends = shownUntil(lines, endUs)
            val starved = lines.indices
                .map { i -> i to needed(lines[i]) - (ends[i] - lines[i].first().startUs) }
                .filter { it.second > 0 }
                .sortedByDescending { it.second }
            val join = starved.firstNotNullOfOrNull { (i, _) ->
                val cap = if (ends[i] - lines[i].first().startUs < FLASH_US) {
                    maxChars * 2
                } else {
                    maxChars * 3 / 2
                }
                partner(lines, i, cap)
            } ?: return lines
            lines[join] = lines[join] + lines.removeAt(join + 1)
        }
    }

    /**
     * When each caption leaves the screen: [HOLD_US] after its last word and at least
     * [MIN_SHOWN_US] after it starts, but never once the next one has started, and never after
     * [endUs] (the end of the clip that speaks it). A gap shorter than [MIN_GAP_US] to the next
     * one is closed.
     */
    fun shownUntil(segments: List<List<Word>>, endUs: Long): List<Long> =
        segments.mapIndexed { i, words ->
            val start = words.first().startUs
            var end = maxOf(words.last().endUs + HOLD_US, start + MIN_SHOWN_US)
            val next = segments.getOrNull(i + 1)?.first()?.startUs
            if (next != null) {
                if (next - end < MIN_GAP_US) end = next
                end = minOf(end, next)
            }
            minOf(end, maxOf(endUs, words.last().endUs))
        }

    /** The first index of the pair [i] joins, or null when no neighbour has room. */
    private fun partner(lines: List<List<Word>>, i: Int, cap: Int): Int? {
        fun fits(a: Int) = chars(lines[a]) + 1 + chars(lines[a + 1]) <= cap
        fun paused(a: Int) = lines[a + 1].first().startUs - lines[a].last().endUs >= MIN_GAP_US
        val options = listOfNotNull(
            (i - 1).takeIf { i > 0 },
            i.takeIf { i + 1 < lines.size },
        ).filter(::fits)
        return options.firstOrNull { !paused(it) } ?: options.firstOrNull()
    }

    private fun needed(words: List<Word>): Long =
        maxOf(MIN_SHOWN_US, chars(words) * 1_000_000L / READING_CPS)

    private fun chars(words: List<Word>): Int = words.sumOf { it.text.length } + words.size - 1

    private val TAGS = mapOf('[' to ']', '(' to ')', '*' to '*')
    private const val MUSIC = "♪♫♬♩"

    /**
     * About two lines of characters for a frame of [width] × [height]: Bold pop fits about 18
     * a line across a portrait frame; wider frames take longer lines, kept short enough to read.
     */
    fun maxChars(width: Int, height: Int): Int = when {
        width < height -> 34
        width == height -> 40
        else -> 48
    }

    fun split(words: List<Word>, maxChars: Int): List<List<Word>> {
        val segments = mutableListOf<MutableList<Word>>()
        var chars = 0
        for (word in words) {
            val current = segments.lastOrNull()
            val previous = current?.lastOrNull()
            val start = current == null ||
                previous == null ||
                word.startUs - previous.endUs >= PAUSE_US ||
                chars + 1 + word.text.length > maxChars ||
                word.endUs - current.first().startUs > MAX_DURATION_US ||
                previous.text.trimEnd().lastOrNull() in SENTENCE_ENDS
            if (start) {
                segments += mutableListOf(word)
                chars = word.text.length
            } else {
                current!!.add(word)
                chars += 1 + word.text.length
            }
        }
        return segments
    }

    private val SENTENCE_ENDS = setOf('.', '?', '!', '…')
}
