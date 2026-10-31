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
 * [MAX_DURATION_US].
 */
object CaptionSegments {
    const val PAUSE_US = 600_000L
    const val MAX_DURATION_US = 5_000_000L

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
