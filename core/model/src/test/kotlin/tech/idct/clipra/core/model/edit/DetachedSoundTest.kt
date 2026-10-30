package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.mediaTimeAt

/** Detached sound (#41): the sound taken off a video stays in sync with it through edits. */
class DetachedSoundTest {
    private val sec = 1_000_000L

    private fun ids(vararg values: String): () -> String {
        val queue = ArrayDeque(values.toList())
        return { queue.removeFirst() }
    }

    // L1: "intro" (0-2 s) then "street" (2-8 s, media 1-7 s). L2: music at 0-1 s and 9-10 s.
    private val project = Project(
        id = "p",
        name = "Detach",
        media = listOf(
            MediaAsset("i", "content://i", MediaKind.Video, durationUs = 2 * sec, hasAudio = true),
            MediaAsset("s", "content://s", MediaKind.Video, durationUs = 10 * sec, hasAudio = true),
            MediaAsset("m", "content://m", MediaKind.Audio, durationUs = 10 * sec),
        ),
        layers = listOf(
            Layer(
                "L1",
                clips = listOf(
                    video("intro", 0, MediaRef("i", 0, 2 * sec)),
                    video("street", 2 * sec, MediaRef("s", 1 * sec, 7 * sec)),
                ),
            ),
            Layer("L2", clips = listOf(music("song", 0), music("outro", 9 * sec))),
        ),
    )

    /** "street" with its sound detached onto L2 as "sound". */
    private val detached = ExtractAudio("street", ids("sound", "g")).apply(project)

    private fun video(id: String, start: Long, media: MediaRef) = Clip(
        id = id,
        type = ClipType.Video,
        startUs = start,
        durationUs = media.outPointUs - media.inPointUs,
        source = media,
    )

    private fun music(id: String, start: Long) = Clip(
        id = id,
        type = ClipType.Audio,
        startUs = start,
        durationUs = 1 * sec,
        source = MediaRef("m", 0, 1 * sec),
    )

    private fun Project.clip(id: String) = findClip(id)!!.second

    private fun Project.layerOf(id: String) = findClip(id)!!.first.id

    private fun Project.without(id: String) = copy(
        layers = layers.map { layer -> layer.copy(clips = layer.clips.filter { it.id != id }) },
    )

    private fun applyAndCheckInverse(command: EditCommand, from: Project = detached): Project {
        val history = EditHistory(from)
        val edited = history.apply(command)
        assertEquals(from, history.undo())
        assertEquals(edited, history.redo())
        return edited
    }

    /** At [atUs] on the timeline, the picture and the sound play the same moment of the media. */
    private fun Project.assertInSync(videoId: String, soundId: String, atUs: Long) {
        val video = clip(videoId)
        val sound = clip(soundId)
        assertTrue("$videoId plays at $atUs", video.contains(atUs))
        assertTrue("$soundId plays at $atUs", sound.contains(atUs))
        assertEquals(
            video.mediaTimeAt(atUs - video.startUs),
            sound.mediaTimeAt(atUs - sound.startUs),
        )
    }

    @Test
    fun theSoundLandsOnAFreeLayerLinked() {
        assertEquals("L2", detached.layerOf("sound"))
        assertEquals(setOf("sound"), detached.linkedPartners("street"))
        assertEquals(0f, detached.clip("street").audio.volume)
        for (at in listOf(2 * sec, 5 * sec, 8 * sec - 1)) {
            detached.assertInSync("street", "sound", at)
        }
    }

    @Test
    fun movesSplitsAndRippleDeletesTakeTheSoundAlong() {
        val moved = applyAndCheckInverse(MoveClip("street", 2_500_000))
        assertEquals(2_500_000L, moved.clip("sound").startUs)
        moved.assertInSync("street", "sound", 4 * sec)

        val split =
            applyAndCheckInverse(SplitClip("street", 5 * sec, ids("street-2", "sound-2", "g2")))
        assertEquals(setOf("sound-2"), split.linkedPartners("street-2"))
        split.assertInSync("street", "sound", 4 * sec)
        split.assertInSync("street-2", "sound-2", 6 * sec)

        // With the music at 0-1 s out of the way, as the sound moves there.
        assertThrows(EditRejected::class.java) { DeleteClip("intro").apply(split) }
        val closed = applyAndCheckInverse(DeleteClip("intro"), split.without("song"))
        assertEquals(0L, closed.clip("street").startUs)
        assertEquals(0L, closed.clip("sound").startUs)
        closed.assertInSync("street-2", "sound-2", 4 * sec)
    }

    @Test
    fun trimsKeepTheSoundInSync() {
        val trimmed = applyAndCheckInverse(TrimClip("street", Edge.Start, 3 * sec))
        // The sound keeps playing from 2 s: an L cut, still in sync where both play.
        assertEquals(2 * sec, trimmed.clip("sound").startUs)
        trimmed.assertInSync("street", "sound", 4 * sec)

        val longer = applyAndCheckInverse(TrimClip("sound", Edge.Start, 1_500_000))
        assertEquals(2 * sec, longer.clip("street").startUs)
        longer.assertInSync("street", "sound", 3 * sec)
    }

    @Test
    fun aSpeedChangeKeepsTheSoundInStep() {
        val fast = applyAndCheckInverse(SetSpeed("street", Speed(rate = 2.0)))
        assertEquals(Speed(rate = 2.0), fast.clip("sound").speed)
        assertEquals(3 * sec, fast.clip("sound").durationUs)
        for (at in listOf(2 * sec, 3 * sec, 5 * sec - 1)) fast.assertInSync("street", "sound", at)
        // The music after the sound closes up with it.
        assertEquals(6 * sec, fast.clip("outro").startUs)

        // Slower from the sound's side: the picture follows, and the music makes room.
        val slow = applyAndCheckInverse(SetSpeed("sound", Speed(rate = 0.5)))
        assertEquals(12 * sec, slow.clip("street").durationUs)
        assertEquals(15 * sec, slow.clip("outro").startUs)
        for (at in listOf(3 * sec, 9 * sec, 13 * sec)) slow.assertInSync("street", "sound", at)

        val ramp = applyAndCheckInverse(SetSpeed("street", Speed(ramp = "hero")))
        assertEquals(ramp.clip("street").durationUs, ramp.clip("sound").durationUs)
        for (at in listOf(3 * sec, 4 * sec, 6 * sec)) ramp.assertInSync("street", "sound", at)
    }

    @Test
    fun aSoundTrimmedApartKeepsItsOwnSpeed() {
        val lCut = TrimClip("sound", Edge.End, 9 * sec).apply(detached.without("outro"))
        val fast = SetSpeed("street", Speed(rate = 2.0)).apply(lCut)
        assertEquals(Speed(), fast.clip("sound").speed)
        assertEquals(7 * sec, fast.clip("sound").durationUs)
    }

    @Test
    fun aFreezeFrameHoldsTheSoundToo() {
        val frozen = applyAndCheckInverse(
            FreezeFrame("street", 4 * sec, 1 * sec, ids("street-2", "sound-2", "g2", "still")),
        )
        // Silent while the frame holds, then the rest of the sound with the rest of the picture.
        assertEquals(2 * sec, frozen.clip("sound").durationUs)
        assertEquals(5 * sec, frozen.clip("sound-2").startUs)
        assertEquals(setOf("sound-2"), frozen.linkedPartners("street-2"))
        frozen.assertInSync("street", "sound", 3 * sec)
        frozen.assertInSync("street-2", "sound-2", 7 * sec)
        assertEquals(10 * sec, frozen.clip("outro").startUs)
    }

    @Test
    fun aSoundTakesTheFirstFreeLayerAtItsTime() {
        val voice = music("voice", 500_000).copy(durationUs = 2 * sec)
        // L2 has music at 0-1 s, so the voiceover goes on a new layer on top.
        val added = applyAndCheckInverse(AddSound(voice, ids("L3")), project)
        assertEquals("L3", added.layerOf("voice"))

        // At 1-3 s, L2 is free.
        val later = AddSound(voice.copy(id = "later", startUs = 1 * sec), ids("L4")).apply(added)
        assertEquals("L2", later.layerOf("later"))

        // A locked layer is passed over.
        val locked = SetLayerLocked("L2", true).apply(added)
        val skipped = AddSound(voice.copy(id = "later", startUs = 9 * sec), ids("L4")).apply(locked)
        assertEquals("L3", skipped.layerOf("later"))
    }
}
