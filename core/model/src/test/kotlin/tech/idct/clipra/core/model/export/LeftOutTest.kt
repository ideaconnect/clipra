package tech.idct.clipra.core.model.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec

/** The clips an export leaves out because their media can't be read (#212). */
class LeftOutTest {
    private val sec = 1_000_000L

    private val tram =
        MediaAsset("tram", "content://1", MediaKind.Video, "tram.mp4", hasAudio = true)
    private val alfama = MediaAsset("alfama", "content://2", MediaKind.Video, "alfama.mp4")
    private val stamp = MediaAsset("stamp", "content://3", MediaKind.Photo, "stamp.png")
    private val fado = MediaAsset("fado", "content://4", MediaKind.Audio, "fado.mp3")

    private fun clip(id: String, type: ClipType, start: Long, media: String?) = Clip(
        id,
        type,
        start * sec,
        4 * sec,
        media?.let { MediaRef(it, 0, 4 * sec) },
        text = if (media == null) TextSpec("Lisbon") else null,
    )

    /** The issue's project in small: video on L1, a photo and a text on L2, music on L3. */
    private fun project(l2: Layer = Layer("L2"), l3: Layer = Layer("L3")) = Project(
        id = "lisbon",
        name = "Lisbon weekend",
        media = listOf(tram, alfama, stamp, fado),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    clip("a", ClipType.Video, 0, "tram"),
                    clip("b", ClipType.Video, 4, "tram"),
                ),
            ),
            l2.copy(
                clips = listOf(
                    clip("photo", ClipType.Photo, 2, "stamp"),
                    clip("title", ClipType.Text, 0, null),
                ),
            ),
            l3.copy(clips = listOf(clip("music", ClipType.Audio, 1, "fado"))),
        ),
    )

    @Test
    fun oneUnreadableFileGivesOneFinding() {
        val left = LeftOut.clips(project()) { it.id != "stamp" }

        assertEquals(
            listOf(LeftOutClip("photo", ClipType.Photo, 2, 2 * sec, 6 * sec, "stamp", "stamp.png")),
            left,
        )
    }

    @Test
    fun everythingReadableLeavesNothingOut() {
        assertTrue(LeftOut.clips(project()) { true }.isEmpty())
    }

    @Test
    fun everyClipOfAMissingFileIsListedInTimeOrderAndEachFileIsAskedOnce() {
        val asked = mutableListOf<String>()
        val left = LeftOut.clips(project()) {
            asked += it.id
            it.id == "stamp"
        }

        assertEquals(listOf("a", "music", "b"), left.map { it.clipId })
        assertEquals(listOf(1, 3, 1), left.map { it.layer })
        assertEquals(listOf("tram.mp4", "fado.mp3", "tram.mp4"), left.map { it.fileName })
        // Text has no media to read; tram is asked once for both of its clips.
        assertEquals(listOf("tram", "stamp", "fado"), asked)
    }

    @Test
    fun clipsThatAddNothingToTheVideoDontCount() {
        val nothingReadable = { _: MediaAsset -> false }

        // A hidden layer draws nothing: its photo isn't missed, but its sound still plays.
        val hidden = LeftOut.clips(
            project(l2 = Layer("L2", hidden = true), l3 = Layer("L3", hidden = true)),
            nothingReadable,
        )
        assertEquals(listOf("a", "music", "b"), hidden.map { it.clipId })

        // A muted layer adds no sound, so its music isn't missed.
        val muted = LeftOut.clips(project(l3 = Layer("L3", muted = true)), nothingReadable)
        assertEquals(listOf("a", "photo", "b"), muted.map { it.clipId })
    }

    @Test
    fun aVideoCountsWhileItShowsOrPlaysSound() {
        fun leftOut(layer: Layer, asset: MediaAsset): List<String> = LeftOut.clips(
            Project(
                id = "p",
                name = "p",
                media = listOf(asset),
                layers = listOf(layer.copy(clips = listOf(clip("v", ClipType.Video, 0, asset.id)))),
            ),
        ) { false }.map { it.clipId }

        assertEquals(listOf("v"), leftOut(Layer("L1", muted = true), alfama))
        assertEquals(listOf("v"), leftOut(Layer("L1", hidden = true), tram))
        assertEquals(emptyList<String>(), leftOut(Layer("L1", hidden = true), alfama))
        assertEquals(emptyList<String>(), leftOut(Layer("L1", hidden = true, muted = true), tram))
    }

    @Test
    fun aClipWhoseMediaIsNotInTheProjectIsLeftOutToo() {
        val project = project().let { it.copy(media = it.media - stamp) }
        val asked = mutableListOf<String>()

        val left = LeftOut.clips(project) {
            asked += it.id
            true
        }

        assertEquals(
            listOf(LeftOutClip("photo", ClipType.Photo, 2, 2 * sec, 6 * sec, "stamp")),
            left,
        )
        assertTrue("stamp" !in asked)
    }
}
