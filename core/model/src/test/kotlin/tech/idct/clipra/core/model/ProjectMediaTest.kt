package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** Imported media joining a project (#214): the same content again takes the new link. */
class ProjectMediaTest {
    private val sec = 1_000_000L

    /** The issue's overlay photo, at the link MediaStore gave it before it was deleted. */
    private val stamp = MediaAsset(
        "stamp",
        "content://media/picker/0/com.android.providers.media.photopicker/media/74",
        MediaKind.Photo,
        displayName = "stamp.png",
        width = 1080,
        height = 1350,
        sizeBytes = 2_400_000,
        takenAtMillis = 1_762_351_200_000,
    )
    private val tram = MediaAsset(
        "tram",
        "content://media/picker/0/com.android.providers.media.photopicker/media/75",
        MediaKind.Video,
        displayName = "tram.mp4",
        durationUs = 10 * sec,
        hasAudio = true,
    )
    private val project = Project(
        id = "p",
        name = "Lisbon weekend",
        media = listOf(tram, stamp),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(clip("tram-a", ClipType.Video, MediaRef("tram", 0, 6 * sec))),
            ),
            Layer("L2", clips = listOf(clip("stamp-a", ClipType.Photo, MediaRef("stamp", 0, 0)))),
        ),
    )

    private fun clip(id: String, type: ClipType, source: MediaRef) =
        Clip(id, type, startUs = 0, durationUs = 3 * sec, source = source)

    /** The same photo put back in its folder: MediaStore gives it a new link. */
    private val restored = stamp.copy(
        uri = "content://media/picker/0/com.android.providers.media.photopicker/media/83",
    )

    @Test
    fun otherMediaJoinsTheProject() {
        val habitat = MediaAsset("habitat", "content://media/76", MediaKind.Audio, "habitat.mp3")

        val added = project.withAsset(habitat)

        assertEquals(listOf(tram, stamp, habitat), added.media)
        assertEquals(project.layers, added.layers)
    }

    @Test
    fun theSameContentFromAnotherLinkTakesThatLink() {
        val relinked = project.withAsset(restored.copy(displayName = "stamp (1).png"))

        assertEquals(
            listOf(tram, stamp.copy(uri = restored.uri, displayName = "stamp (1).png")),
            relinked.media,
        )
        // The clips keep playing the same media, as they were.
        assertEquals(project.layers, relinked.layers)
    }

    @Test
    fun theProjectKeepsWhatDescribesItsMedia() {
        // What the import says about the file may differ from what the project knows; the
        // project's own description stays, as with Find file (#216).
        val picked = restored.copy(width = 0, height = 0, recorded = true, sizeBytes = 1)

        val relinked = project.withAsset(picked).asset("stamp")!!

        assertEquals(stamp.copy(uri = restored.uri), relinked)
    }

    @Test
    fun aProjectFromBefore216LearnsTheSizeAndDateTaken() {
        val old = project.copy(media = listOf(stamp.copy(sizeBytes = 0, takenAtMillis = 0)))

        val relinked = old.withAsset(restored).asset("stamp")!!

        assertEquals(restored, relinked)
    }

    @Test
    fun aNameTheImportCouldNotReadKeepsTheKnownOne() {
        val relinked = project.withAsset(restored.copy(displayName = "")).asset("stamp")!!

        assertEquals("stamp.png", relinked.displayName)
        assertEquals(restored.uri, relinked.uri)
    }

    @Test
    fun theSameFileAgainChangesNothing() {
        assertSame(project, project.withAsset(stamp))
    }
}
