package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextColors
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.TextTemplate
import tech.idct.clipra.core.model.TextTemplates
import tech.idct.clipra.core.model.Transform

/** Text templates (#34): one tap gives a clip a look, and the look can be kept and reused. */
class TextTemplateTest {
    private val sec = 1_000_000L

    private val video = Clip("v", ClipType.Video, 0, 10 * sec, MediaRef("v", 0, 10 * sec))
    private val overlay = Clip(
        "words",
        ClipType.Text,
        0,
        3 * sec,
        text = TextSpec("Day two"),
        transform = Keyframed(Transform(x = 0.1f)),
    )
    private val card = Clip(
        "card",
        ClipType.TextCard,
        3 * sec,
        3 * sec,
        text = TextSpec("Lisbon", background = TextColors.RED),
    )

    private val project = Project(
        id = "p",
        name = "p",
        layers = listOf(
            Layer("L1", clips = listOf(video)),
            Layer("L2", clips = listOf(overlay, card)),
        ),
    )

    private fun template(id: String) = TextTemplates.builtIn.single { it.id == id }

    private fun Project.clip(id: String) = findClip(id)!!.second

    @Test
    fun aTemplateGivesItsLookAndKeepsTheWords() {
        val lowerThird = template("lower_third")
        val clip = ApplyTextTemplate("words", lowerThird).apply(project).clip("words")
        assertEquals(lowerThird.spec.copy(text = "Day two"), clip.text)
        // Words over a video go where the template puts them, keeping their other placing.
        assertEquals(Transform(x = 0.1f, y = 0.3f), clip.transform.value)
        assertEquals(ClipType.Text, clip.type)
    }

    @Test
    fun aCardKeepsACardAndWordsOverAVideoGetNone() {
        // Title has no card colour: the card keeps its red and stays a card.
        val title = ApplyTextTemplate("card", template("title")).apply(project).clip("card")
        assertEquals(TextColors.RED, title.text?.background)
        assertEquals("Lisbon", title.text?.text)
        assertEquals(ClipType.TextCard, title.type)
        // Text card's black goes on a card, but never behind words over a video.
        val black = ApplyTextTemplate("card", template("card")).apply(project).clip("card")
        assertEquals(TextColors.BLACK, black.text?.background)
        val words = ApplyTextTemplate("words", template("card")).apply(project).clip("words")
        assertNull(words.text?.background)
        assertEquals(ClipType.Text, words.type)
    }

    @Test
    fun aMovingClipKeepsItsPlace() {
        val moving = overlay.copy(
            transform = Keyframed(
                Transform(),
                listOf(Keyframe(0, Transform()), Keyframe(sec, Transform(y = -0.2f))),
            ),
        )
        val withMoving = project.replaceClip(moving)
        val clip = ApplyTextTemplate("words", template("subtitle")).apply(withMoving).clip("words")
        assertEquals(moving.transform, clip.transform)
        assertEquals("shadow", clip.text?.effect)
    }

    @Test
    fun onlyTextTakesATemplateAndNotOnALockedLayer() {
        assertThrows(EditRejected::class.java) {
            ApplyTextTemplate("v", template("title")).apply(project)
        }
        val locked = project.copy(
            layers = listOf(project.layers[0], project.layers[1].copy(locked = true)),
        )
        assertThrows(EditRejected::class.java) {
            ApplyTextTemplate("words", template("title")).apply(locked)
        }
    }

    @Test
    fun aSavedTemplateKeepsTheClipsLookAndPlace() {
        val placed = overlay.copy(
            text = TextSpec("Sunset", weight = 500, effect = "glow"),
            transform = Keyframed(Transform(y = 0.25f)),
        )
        val saved = TextTemplates.of(placed, "a")!!
        assertEquals("saved:a", saved.id)
        assertEquals(placed.text, saved.spec)
        assertEquals(0.25f, saved.y)
        // A card has no place to keep; it fills the frame.
        assertNull(TextTemplates.of(card, "b")!!.y)
        assertNull(TextTemplates.of(video, "c"))

        // Applied to another clip, it gives that clip the same look in the same place.
        val applied = ApplyTextTemplate("words", saved).apply(project).clip("words")
        assertEquals(placed.text!!.copy(text = "Day two"), applied.text)
        assertEquals(0.25f, applied.transform.value.y)
    }

    @Test
    fun savedTemplatesSurviveAsJson() {
        val saved = listOf(
            TextTemplates.of(overlay, "a")!!,
            TextTemplate("saved:b", TextSpec("Lisbon", background = TextColors.BLACK), null),
        )
        assertEquals(saved, TextTemplates.decode(TextTemplates.encode(saved)))
        assertTrue(TextTemplates.decode(null).isEmpty())
        assertTrue(TextTemplates.decode("not json").isEmpty())
    }

    @Test
    fun theBuiltInsAreTheBoardsSix() {
        assertEquals(
            listOf("card", "title", "lower_third", "subtitle", "callout", "end_credits"),
            TextTemplates.builtIn.map { it.id },
        )
        // Only Text card has a card colour, so it is the only one that makes words a card.
        assertEquals(
            listOf("card"),
            TextTemplates.builtIn.filter { it.spec.background != null }.map { it.id },
        )
    }
}
