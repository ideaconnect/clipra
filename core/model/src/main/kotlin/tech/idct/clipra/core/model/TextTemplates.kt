package tech.idct.clipra.core.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A text look to start from (#34): [spec]'s font, weight, size, colours, alignment and effect,
 * and, for words over a video, where they sit ([y], from the frame's middle, as `Transform.y`).
 * [spec]'s words are what the template's tile shows; applying it keeps the clip's own words.
 */
@Serializable
data class TextTemplate(val id: String, val spec: TextSpec, val y: Float? = null)

/** The built-in templates of spec section 10, and the user's saved ones as JSON. */
object TextTemplates {
    /** Ids of templates the user saved start with this. */
    const val SAVED = "saved:"

    /** Text card, Title, Lower third, Subtitle, Callout and End credits, as the board orders them. */
    val builtIn: List<TextTemplate> = listOf(
        TextTemplate(
            "card",
            TextSpec(
                "DAY TWO",
                font = TextFonts.BRICOLAGE,
                weight = 800,
                size = 0.09f,
                color = TextColors.WHITE,
                background = TextColors.BLACK,
            ),
        ),
        TextTemplate(
            "title",
            TextSpec(
                "TITLE",
                font = TextFonts.BRICOLAGE,
                weight = 800,
                size = 0.1f,
                color = TextColors.WHITE,
                effect = "shadow",
            ),
        ),
        TextTemplate(
            "lower_third",
            TextSpec(
                "Lisbon, PT",
                font = TextFonts.GEIST,
                weight = 700,
                size = 0.045f,
                color = TextColors.WHITE,
                align = TextAlign.Start,
                effect = "box",
            ),
            y = 0.3f,
        ),
        TextTemplate(
            "subtitle",
            TextSpec(
                "Subtitle",
                font = TextFonts.GEIST,
                weight = 500,
                size = 0.04f,
                color = TextColors.WHITE,
                effect = "shadow",
            ),
            y = 0.38f,
        ),
        TextTemplate(
            "callout",
            TextSpec(
                "Look!",
                font = TextFonts.BRICOLAGE,
                weight = 800,
                size = 0.06f,
                color = TextColors.WHITE,
                effect = "marker",
            ),
        ),
        TextTemplate(
            "end_credits",
            TextSpec(
                "The end",
                font = TextFonts.GEIST,
                weight = 500,
                size = 0.045f,
                color = TextColors.WHITE,
            ),
        ),
    )

    /** [clip]'s look as a template to save, its words kept as the tile's sample. */
    fun of(clip: Clip, id: String): TextTemplate? {
        val spec = clip.text ?: return null
        val y = clip.transform.value.y.takeIf { clip.type == ClipType.Text && it != 0f }
        return TextTemplate(SAVED + id, spec, y)
    }

    fun encode(templates: List<TextTemplate>): String =
        json.encodeToString(ListSerializer(TextTemplate.serializer()), templates)

    /** The saved templates in [text]; none when it is missing or can't be read. */
    fun decode(text: String?): List<TextTemplate> = if (text.isNullOrBlank()) {
        emptyList()
    } else {
        runCatching {
            json.decodeFromString(ListSerializer(TextTemplate.serializer()), text)
        }.getOrDefault(emptyList())
    }

    private val json = Json { ignoreUnknownKeys = true }
}
