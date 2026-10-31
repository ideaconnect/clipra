package tech.idct.clipra.core.model.export

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.Transition
import tech.idct.clipra.core.model.Word
import java.io.File

/** Export extras (#59): which files an export saves, and the project as OpenTimelineIO. */
class ExportExtrasTest {
    private val sec = 1_000_000L

    private val tram = MediaAsset(
        "tram",
        "content://media/external/video/media/41",
        MediaKind.Video,
        displayName = "Tram 28.mp4",
        durationUs = 12 * sec,
        hasAudio = true,
    )
    private val view = MediaAsset(
        "view",
        "content://media/external/video/media/42",
        MediaKind.Video,
        displayName = "Miradouro.mp4",
        durationUs = 8 * sec,
        hasAudio = false,
    )
    private val voice = MediaAsset(
        "voice",
        "/data/user/0/tech.idct.clipra/files/voiceovers/take 1.m4a",
        MediaKind.Audio,
        displayName = "Voiceover 1",
        durationUs = 5 * sec,
        hasAudio = true,
        recorded = true,
    )

    private val caption = Clip(
        "cap",
        ClipType.Caption,
        sec,
        2 * sec,
        caption = CaptionSpec(
            listOf(Word("We", sec, 1_400_000), Word("made", 1_400_000, 2_000_000)),
        ),
    )

    /** Lisbon in short: two shots with a dissolve, a title, a caption and a voiceover. */
    private val project = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        format = Format(ratio = AspectRatio.Landscape16x9),
        media = listOf(tram, view, voice),
        layers = listOf(
            Layer(
                "base",
                clips = listOf(
                    Clip("a", ClipType.Video, 0, 4 * sec, MediaRef("tram", 2 * sec, 6 * sec)),
                    Clip(
                        "b",
                        ClipType.Video,
                        4 * sec,
                        2 * sec,
                        MediaRef("view", 0, 4 * sec),
                        speed = Speed(rate = 2.0),
                    ),
                ),
            ),
            Layer(
                "words",
                clips = listOf(
                    Clip("title", ClipType.Text, sec, 2 * sec, text = TextSpec("DAY ONE")),
                    caption.copy(startUs = 3_500_000),
                ),
            ),
            Layer(
                "voice",
                muted = true,
                clips = listOf(
                    Clip(
                        "vo",
                        ClipType.Audio,
                        500_000,
                        5 * sec,
                        MediaRef("voice", 0, 5 * sec),
                        audio = AudioSettings(volume = 0.8f),
                    ),
                ),
            ),
        ),
        transitions = listOf(
            Transition("t", "base", 4 * sec, "dissolve", durationUs = 600_000),
        ),
    )

    private fun otio(project: Project): JsonObject =
        Json.parseToJsonElement(Otio.write(project)).jsonObject

    private val JsonObject.schema get() = getValue("OTIO_SCHEMA").jsonPrimitive.content
    private val JsonObject.name get() = getValue("name").jsonPrimitive.content
    private val JsonObject.children get() = getValue("children").jsonArray.map { it.jsonObject }

    /** A RationalTime's value, in frames. */
    private fun JsonObject.frames(key: String) =
        getValue(key).jsonObject.getValue("value").jsonPrimitive.double

    private fun JsonObject.range(key: String = "source_range") = getValue(key).jsonObject.let {
        it.frames("start_time") to it.frames("duration")
    }

    @Test
    fun captionsAreSavedWhenTheyAreASeparateFileOrAsked() {
        val withCaptions = project
        assertEquals(emptySet<ExportExtra>(), ExportSettings().savedExtras(withCaptions))
        assertEquals(
            setOf(ExportExtra.Captions),
            ExportSettings(captions = CaptionMode.Srt).savedExtras(withCaptions),
        )
        // Burned in and saved as .srt too.
        assertEquals(
            setOf(ExportExtra.Captions, ExportExtra.Cover),
            ExportSettings(extras = setOf(ExportExtra.Captions, ExportExtra.Cover))
                .savedExtras(withCaptions),
        )
        // No captions, no .srt.
        val noCaptions = project.copy(layers = project.layers.take(1))
        assertEquals(
            emptySet<ExportExtra>(),
            ExportSettings(captions = CaptionMode.Srt).savedExtras(noCaptions),
        )
    }

    @Test
    fun soundIsSavedOnlyWhenSomethingPlaysSome() {
        val audio = ExportSettings(extras = setOf(ExportExtra.Audio))
        assertTrue(project.hasSound)
        assertEquals(setOf(ExportExtra.Audio), audio.savedExtras(project))
        // The view has no sound track, and the title plays none.
        val silent = project.copy(
            layers = listOf(
                project.layers[0].copy(clips = project.layers[0].clips.drop(1)),
                project.layers[1],
            ),
        )
        assertFalse(silent.hasSound)
        assertEquals(emptySet<ExportExtra>(), audio.savedExtras(silent))
    }

    @Test
    fun captionsAreDrawnOnlyWhenBurnedIn() {
        fun captions(p: Project) = p.layers.flatMap { it.clips }.count {
            it.type == ClipType.Caption
        }
        assertEquals(1, captions(ExportSettings().drawn(project)))
        assertEquals(0, captions(ExportSettings(captions = CaptionMode.Srt).drawn(project)))
        assertEquals(0, captions(ExportSettings(captions = CaptionMode.Off).drawn(project)))
        // Everything else stays.
        assertEquals(
            listOf("a", "b", "title", "vo"),
            ExportSettings(captions = CaptionMode.Off).drawn(project).layers
                .flatMap { layer -> layer.clips.map { it.id } },
        )
    }

    /** Tracks are named as Clipra shows the layers, whatever their ids. */
    @Test
    fun theTimelineHasATrackPerLayerAndKindWithL1AtTheBottom() {
        val timeline = otio(project)
        assertEquals("Timeline.1", timeline.schema)
        assertEquals("Lisbon weekend", timeline.name)
        val tracks = timeline.getValue("tracks").jsonObject.children
        assertEquals(
            listOf("L1" to "Video", "L1" to "Audio", "L2" to "Video", "L3" to "Audio"),
            tracks.map { it.name to it.getValue("kind").jsonPrimitive.content },
        )
        // A muted layer's sound track is off.
        assertFalse(tracks[3].getValue("enabled").jsonPrimitive.content.toBoolean())
        assertTrue(tracks[0].getValue("enabled").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun clipsKeepTheirTimingsInFramesWithGapsAndTransitions() {
        val tracks = otio(project).getValue("tracks").jsonObject.children
        val l1 = tracks[0].children
        assertEquals(listOf("Clip.1", "Transition.1", "Clip.1"), l1.map { it.schema })
        // a plays seconds 2 to 6 of the tram for 4 s, at 30 fps.
        assertEquals(60.0 to 120.0, l1[0].range())
        assertEquals("Tram 28.mp4", l1[0].name)
        val reference = l1[0].getValue("media_reference").jsonObject
        assertEquals("ExternalReference.1", reference.schema)
        assertEquals(
            "content://media/external/video/media/41",
            reference.getValue("target_url").jsonPrimitive.content,
        )
        assertEquals(0.0 to 360.0, reference.range("available_range"))
        // The dissolve, 0.6 s around the cut.
        assertEquals("SMPTE_Dissolve", l1[1].getValue("transition_type").jsonPrimitive.content)
        assertEquals(9.0, l1[1].frames("in_offset"), 0.0)
        assertEquals(9.0, l1[1].frames("out_offset"), 0.0)
        // b at double speed: 2 s on the timeline, a LinearTimeWarp of 2.
        assertEquals(0.0 to 60.0, l1[2].range())
        val warp = (l1[2].getValue("effects") as JsonArray).single().jsonObject
        assertEquals("LinearTimeWarp.1", warp.schema)
        assertEquals(2.0, warp.getValue("time_scalar").jsonPrimitive.double, 0.0)

        // Only the tram has sound, so L1's sound track has a 4 s clip then nothing.
        assertEquals(listOf("a"), tracks[1].children.map { clipId(it) })

        // L2: a second's gap, the title, half a second's gap, the caption.
        val l2 = tracks[2].children
        assertEquals(listOf("Gap.1", "Clip.1", "Gap.1", "Clip.1"), l2.map { it.schema })
        assertEquals(0.0 to 30.0, l2[0].range())
        assertEquals("DAY ONE", l2[1].name)
        assertEquals(
            "MissingReference.1",
            l2[1].getValue("media_reference").jsonObject.schema,
        )
        assertEquals(0.0 to 15.0, l2[2].range())
        assertEquals("We made", l2[3].name)

        // L3: half a second, then the voiceover, a file of the app's own as a file URL.
        val l3 = tracks[3].children
        assertEquals(15.0, l3[0].range().second, 0.0)
        assertEquals(
            "file:///data/user/0/tech.idct.clipra/files/voiceovers/take%201.m4a",
            l3[1].getValue("media_reference").jsonObject.getValue("target_url").jsonPrimitive
                .content,
        )
    }

    @Test
    fun aSampleOpensInOpenTimelineIo() {
        // Written for a check with the OpenTimelineIO library, outside the build (see the PR).
        File("build/otio").mkdirs()
        File("build/otio/lisbon.otio").writeText(Otio.write(project))
    }

    private fun clipId(item: JsonObject) = item.getValue("metadata").jsonObject.getValue("clipra")
        .jsonObject.getValue("id").jsonPrimitive.content
}
