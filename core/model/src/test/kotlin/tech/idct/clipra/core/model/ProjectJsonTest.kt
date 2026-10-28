package tech.idct.clipra.core.model

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ProjectJsonTest {
    private val projectJson = ProjectJson()

    private val project = Project(
        id = "p1",
        name = "Lisbon weekend",
        format = Format(
            ratio = AspectRatio.Portrait9x16,
            resolution = Resolution.FHD,
            frameRate = FrameRate.Fps30,
        ),
        createdAtMillis = 1_761_600_000_000,
        media = listOf(
            MediaAsset(
                id = "sha256-abc",
                uri = "content://media/picker/0/video/42",
                kind = MediaKind.Video,
                displayName = "sunset.mp4",
                durationUs = 12_000_000,
                width = 1080,
                height = 1920,
                hasAudio = true,
            ),
        ),
        layers = listOf(
            Layer(
                id = "L1",
                clips = listOf(
                    Clip(
                        id = "c1",
                        type = ClipType.Video,
                        startUs = 0,
                        durationUs = 9_200_000,
                        source = MediaRef(
                            "sha256-abc",
                            inPointUs = 500_000,
                            outPointUs = 9_700_000,
                        ),
                        opacity = Keyframed(
                            1f,
                            listOf(Keyframe(0, 0f), Keyframe(400_000, 1f, Easing.Linear)),
                        ),
                        blend = BlendMode.Screen,
                        effects = listOf(Effect("warm", 0.8f)),
                        audio = AudioSettings(volume = 1.2f, loudnessTarget = -14),
                    ),
                ),
            ),
            Layer(
                id = "L2",
                muted = true,
                clips = listOf(
                    Clip(
                        id = "t1",
                        type = ClipType.Text,
                        startUs = 1_000_000,
                        durationUs = 3_000_000,
                        text = TextSpec("Day one"),
                        transform = Keyframed(Transform(y = -0.3f, scale = 0.8f)),
                    ),
                ),
            ),
        ),
        transitions = listOf(Transition("tr1", "L1", 9_200_000, "dissolve")),
        links = listOf(LinkGroup("g1", listOf("c1", "t1"))),
        transcripts = listOf(
            Transcript(
                "c1",
                "en",
                listOf(Word("We", 0, 200_000), Word("um", 200_000, 500_000, 0.6f, WordKind.Filler)),
            ),
        ),
    )

    @Test
    fun roundTripKeepsEverything() {
        val text = projectJson.encode(project)
        assertEquals(project, projectJson.decode(text))
    }

    @Test
    fun documentsCarryTheSchemaVersion() {
        val document = projectJson.encodeToObject(project)
        assertEquals(
            ProjectJson.SCHEMA_VERSION,
            document["version"]!!.jsonPrimitive.content.toInt(),
        )
    }

    @Test
    fun timesAreMicrosecondsAndNoCachePaths() {
        val text = projectJson.encode(project)
        assertTrue(text.contains("\"durationUs\":9200000"))
        assertFalse(text.contains("/data/"))
        assertFalse(text.contains("cache"))
    }

    @Test
    fun unknownFieldsFromANewerBuildAreIgnored() {
        val document = projectJson.encodeToObject(project)
        val withExtra = JsonObject(document + ("futureField" to JsonPrimitive(true)))
        assertEquals(project, projectJson.decode(withExtra.toString()))
    }

    @Test
    fun migrationsRunInOrderUpToTheCurrentVersion() {
        // Version 1 named the project "title"; version 2 calls it "name" (a test-only schema).
        val renameTitle = ProjectJson.Migration { document ->
            val title = document["title"]!!
            JsonObject(document - "title" + ("name" to title))
        }
        val migrating = ProjectJson(migrations = listOf(renameTitle))
        val v1 = """{"version":1,"id":"p1","title":"Old project","format":{"ratio":"Square1x1"}}"""

        val migrated = migrating.decode(v1)

        assertEquals("Old project", migrated.name)
        assertEquals(AspectRatio.Square1x1, migrated.format.ratio)
        assertEquals(2, migrated.version)
    }

    @Test
    fun aDocumentFromANewerVersionIsRefused() {
        val future = """{"version":${ProjectJson.SCHEMA_VERSION + 1},"id":"p1","name":"x"}"""
        assertThrows(ProjectJson.NewerVersionException::class.java) { projectJson.decode(future) }
    }

    @Test
    fun durationIsWhereTheLastClipEnds() {
        assertEquals(9_200_000, project.durationUs)
        assertEquals(0, Project(id = "empty", name = "Empty").durationUs)
    }

    private fun ProjectJson.encodeToObject(project: Project): JsonObject =
        kotlinx.serialization.json.Json.parseToJsonElement(encode(project)).jsonObject
}
