package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Effect
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.SpeedCurve

/** Relinking missing media (#216): the media gets a file again, and its clips stay as edited. */
class RelinkMediaTest {
    private val sec = 1_000_000L

    private val tram = MediaAsset(
        "tram",
        "content://media/picker/0/com.android.providers.media.photopicker/media/74",
        MediaKind.Video,
        displayName = "74.mp4",
        durationUs = 10 * sec,
        sizeBytes = 48_300_000,
        takenAtMillis = 1_762_351_200_000,
    )
    private val stamp = MediaAsset("stamp", "content://media/75", MediaKind.Photo, "stamp.png")
    private val habitat =
        MediaAsset(
            "habitat",
            "content://media.documents/audio%3A75",
            MediaKind.Audio,
            "habitat.mp3",
        )

    // L1: tram split at 6.5 s, the second part with the Hero ramp and a filter. L2: the same
    // tram again, reversed at half speed, and a photo. L3 (locked): music.
    private val project = Project(
        id = "p",
        name = "Lisbon weekend",
        media = listOf(tram, stamp, habitat.copy(durationUs = 30 * sec)),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    Clip("tram-a", ClipType.Video, 0, 6_500_000, MediaRef("tram", 0, 6_500_000)),
                    Clip(
                        "tram-b",
                        ClipType.Video,
                        6_500_000,
                        SpeedCurve.timelineDuration(Speed(ramp = "hero"), 3_500_000),
                        MediaRef("tram", 6_500_000, 10 * sec),
                        speed = Speed(ramp = "hero"),
                        effects = listOf(Effect("vivid")),
                    ),
                ),
            ),
            Layer(
                "L2",
                clips = listOf(
                    Clip(
                        "tram-slow",
                        ClipType.Video,
                        0,
                        4 * sec,
                        MediaRef("tram", 1 * sec, 3 * sec),
                        speed = Speed(rate = 0.5, reverse = true),
                    ),
                    Clip("stamp", ClipType.Photo, 5 * sec, 3 * sec, MediaRef("stamp", 0, 0)),
                ),
            ),
            Layer(
                "L3",
                locked = true,
                clips = listOf(
                    Clip(
                        "music",
                        ClipType.Audio,
                        0,
                        20 * sec,
                        MediaRef("habitat", 5 * sec, 25 * sec),
                    ),
                ),
            ),
        ),
    )

    private fun Project.clip(id: String) = findClip(id)!!.second

    /** Applies through the history and checks that undo and redo give back both projects. */
    private fun applyAndCheckInverse(command: EditCommand): Project {
        val history = EditHistory(project)
        val edited = history.apply(command)
        assertTrue(history.canUndo)
        assertEquals(project, history.undo())
        assertEquals(edited, history.redo())
        return edited
    }

    @Test
    fun theSameFileChangesOnlyTheLinkAndEveryClipComesBack() {
        // The file came back from a backup, so MediaStore gave it a new id: a new URI.
        val restored = tram.copy(uri = "content://media/picker/0/media/83", displayName = "83.mp4")

        val edited = applyAndCheckInverse(RelinkMedia("tram", restored))

        assertEquals("content://media/picker/0/media/83", edited.asset("tram")!!.uri)
        // Only the link: the name, the clips (in and out points, speed, effects) stay as edited.
        assertEquals("74.mp4", edited.asset("tram")!!.displayName)
        assertEquals(project.layers, edited.layers)
        assertEquals(project.copy(media = edited.media), edited)
        assertEquals(6_500_000L, edited.clip("tram-b").source!!.inPointUs)
        assertEquals("hero", edited.clip("tram-b").speed.ramp)
    }

    @Test
    fun theSameFileGivesAnOlderProjectItsSizeAndDate() {
        val old = project.copy(
            media = project.media.map {
                if (it.id == "tram") it.copy(sizeBytes = 0, takenAtMillis = 0) else it
            },
        )

        val edited = RelinkMedia("tram", tram.copy(uri = "content://83")).apply(old)

        assertEquals(48_300_000L, edited.asset("tram")!!.sizeBytes)
        assertEquals(1_762_351_200_000L, edited.asset("tram")!!.takenAtMillis)
    }

    @Test
    fun anotherFileKeepsEachInPointWhereItIsLongEnough() {
        // A 12 s re-encode: every part of the 10 s original is in it.
        val other = tram.copy(id = "tram-hd", uri = "content://84", durationUs = 12 * sec)

        val edited = applyAndCheckInverse(RelinkMedia("tram", other))

        assertNull(edited.asset("tram"))
        assertEquals(other, edited.asset("tram-hd"))
        // In the missing media's place in the list.
        assertEquals(listOf("tram-hd", "stamp", "habitat"), edited.media.map { it.id })
        assertEquals(MediaRef("tram-hd", 0, 6_500_000), edited.clip("tram-a").source)
        val second = edited.clip("tram-b")
        assertEquals(MediaRef("tram-hd", 6_500_000, 10 * sec), second.source)
        assertEquals(project.clip("tram-b").durationUs, second.durationUs)
        assertEquals(Speed(ramp = "hero"), second.speed)
        assertEquals(listOf(Effect("vivid")), second.effects)
        assertEquals(Speed(rate = 0.5, reverse = true), edited.clip("tram-slow").speed)
        assertEquals(MediaRef("tram-hd", 1 * sec, 3 * sec), edited.clip("tram-slow").source)
    }

    @Test
    fun aPartPastTheEndOfAShorterFileMovesBackJustEnough() {
        val other = tram.copy(id = "tram-cut", uri = "content://85", durationUs = 8 * sec)

        val edited = RelinkMedia("tram", other).apply(project)

        // 0-6.5 s and 1-3 s are in it; 6.5-10 s now plays its last 3.5 s, at the same speed.
        assertEquals(MediaRef("tram-cut", 0, 6_500_000), edited.clip("tram-a").source)
        assertEquals(MediaRef("tram-cut", 1 * sec, 3 * sec), edited.clip("tram-slow").source)
        val second = edited.clip("tram-b")
        assertEquals(MediaRef("tram-cut", 4_500_000, 8 * sec), second.source)
        assertEquals(project.clip("tram-b").durationUs, second.durationUs)
        assertEquals(6_500_000L, second.startUs)
        assertEquals(Speed(ramp = "hero"), second.speed)
        assertEquals(1 to 3, RelinkMedia.refitted(project, "tram", other))
    }

    @Test
    fun aFileShorterThanAClipsPartPlaysWholeInAShorterClip() {
        val other = tram.copy(id = "tram-short", uri = "content://86", durationUs = 2 * sec)

        val edited = RelinkMedia("tram", other).apply(project)

        // 6.5 s at normal speed can't come from 2 s of media: the clip plays all of it.
        val first = edited.clip("tram-a")
        assertEquals(MediaRef("tram-short", 0, 2 * sec), first.source)
        assertEquals(2 * sec, first.durationUs)
        assertEquals(0L, first.startUs)
        // The 2 s part of the half speed clip fits.
        assertEquals(MediaRef("tram-short", 0, 2 * sec), edited.clip("tram-slow").source)
        assertEquals(4 * sec, edited.clip("tram-slow").durationUs)
        // The ramp plays the whole file, shorter than before.
        val second = edited.clip("tram-b")
        assertEquals(MediaRef("tram-short", 0, 2 * sec), second.source)
        assertEquals(SpeedCurve.timelineDuration(Speed(ramp = "hero"), 2 * sec), second.durationUs)
        assertEquals(3 to 3, RelinkMedia.refitted(project, "tram", other))
    }

    @Test
    fun aMissingSoundOnALockedLayerIsRelinkedToo() {
        // Relinking edits the media, not a clip, so a locked layer's clips come back as well.
        val restored = habitat.copy(uri = "content://externalstorage/primary%3AMusic%2Fhabitat.mp3")

        val edited = RelinkMedia("habitat", restored).apply(project)

        assertEquals(restored.uri, edited.asset("habitat")!!.uri)
        assertEquals(project.clip("music"), edited.clip("music"))
    }

    @Test
    fun aPhotoHasNoPartToFit() {
        val other = stamp.copy(id = "stamp-2", uri = "content://87")

        val edited = RelinkMedia("stamp", other).apply(project)

        assertEquals(MediaRef("stamp-2", 0, 0), edited.clip("stamp").source)
        assertEquals(3 * sec, edited.clip("stamp").durationUs)
    }

    @Test
    fun aFileAlreadyInTheProjectIsShared() {
        val withCopy = project.copy(media = project.media + tram.copy(id = "copy", uri = "c://9"))

        val edited = RelinkMedia("tram", withCopy.asset("copy")!!).apply(withCopy)

        assertEquals(listOf("stamp", "habitat", "copy"), edited.media.map { it.id })
        assertEquals("copy", edited.clip("tram-a").source!!.mediaId)
    }

    @Test
    fun anotherKindOrUnknownMediaIsRefused() {
        val photo = stamp.copy(id = "photo", uri = "content://88")
        val wrongKind = assertThrows(EditRejected::class.java) {
            RelinkMedia("tram", photo).apply(project)
        }
        assertEquals(EditRejected.Reason.WrongKind, wrongKind.reason)
        val unknown = assertThrows(EditRejected::class.java) {
            RelinkMedia("gone", tram).apply(project)
        }
        assertEquals(EditRejected.Reason.ClipNotFound, unknown.reason)
    }
}
