package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.CaptionSpec
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Ducking
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.VoiceCleanup

/**
 * Ducking (#40): the music under a voice goes down while the voice speaks, fully down within
 * 100 ms of the speech starting and back after it, by the voice's depth; the voice itself and
 * its own media are left alone.
 */
class DuckingRenderTest {
    private val sec = 1_000_000L

    // The voice (L2) starts 2 s into the timeline and speaks from 1 s to 3 s of its media.
    private val voice = Clip(
        "voice",
        ClipType.Audio,
        2 * sec,
        5 * sec,
        MediaRef("vo", 0, 5 * sec),
        audio = AudioSettings(ducking = 12f),
    )
    private val music = Clip("music", ClipType.Audio, 0, 10 * sec, MediaRef("song", 0, 10 * sec))
    private val project = Project(
        id = "duck",
        name = "Duck",
        media = listOf(
            MediaAsset("vo", "file://vo", MediaKind.Audio, durationUs = 5 * sec),
            MediaAsset("song", "file://song", MediaKind.Audio, durationUs = 10 * sec),
        ),
        layers = listOf(Layer("L1", clips = listOf(music)), Layer("L2", clips = listOf(voice))),
    )
    private val media = object : MediaResolver {
        override fun source(asset: MediaAsset): String = asset.uri

        override fun image(asset: MediaAsset): Bitmap? = null

        override fun text(spec: TextSpec, card: Boolean, width: Int, height: Int): Bitmap? = null

        override fun caption(caption: CaptionSpec, current: Int, width: Int, height: Int): Bitmap? =
            null

        override fun voice(asset: MediaAsset, cleanup: VoiceCleanup?, noise: Float?): String? = null

        override fun speech(asset: MediaAsset): List<LongRange>? =
            if (asset.id == "vo") listOf(sec..3 * sec) else null
    }

    @Test
    fun theMusicDucksUnderTheVoice() {
        val timeline = ProjectRender.timeline(project, media, 360, 640)
        val song = timeline.layers[0].clips.single()
        val vo = timeline.layers[1].clips.single()
        // The voice speaks from 3 s to 5 s of the timeline: 3 s to 5 s into the music clip.
        assertEquals(listOf(RenderDuck(listOf(3 * sec..5 * sec), 12f)), song.ducks)
        assertTrue(vo.ducks.isEmpty())

        val gain = ClipGain(1f, 0, 0, song.durationUs, ducks = song.ducks)
        // Full volume until just before the voice, fully down 10 ms into it, back after it.
        assertEquals(1f, gain.gainAt(3 * sec - 100_000), 1e-6f)
        val down = Math.pow(10.0, -12.0 / 20).toFloat()
        assertEquals(down, gain.gainAt(3 * sec + Ducking.ATTACK_US - Ducking.LEAD_US), 1e-4f)
        assertEquals(down, gain.gainAt(4 * sec), 1e-4f)
        assertTrue(gain.gainAt(5 * sec + 150_000) in down..1f)
        assertEquals(1f, gain.gainAt(5 * sec + Ducking.RELEASE_US + 1), 1e-6f)
    }

    @Test
    fun followingSpeechWithin100Ms() {
        // From silence to fully down takes the lead and the attack, ending 10 ms into the
        // speech: well inside 100 ms; half way down already at the speech's start.
        val speech = listOf(sec..2 * sec)
        assertEquals(1f, Ducking.weight(sec + 10_000, speech), 1e-6f)
        assertTrue(Ducking.weight(sec, speech) > 0.5f)
        assertEquals(0f, Ducking.weight(sec - Ducking.LEAD_US - 1, speech), 0f)
        assertEquals(0f, Ducking.weight(2 * sec + Ducking.RELEASE_US, speech), 0f)
        assertTrue(Ducking.LEAD_US + Ducking.ATTACK_US - Ducking.LEAD_US <= 100_000)
    }

    @Test
    fun withoutSpeechOrDuckingNothingChanges() {
        val quiet = object : MediaResolver by media {
            override fun speech(asset: MediaAsset): List<LongRange>? = null
        }
        val timeline = ProjectRender.timeline(project, quiet, 360, 640)
        assertTrue(timeline.layers.flatMap { it.clips }.all { it.ducks.isEmpty() })
        val undecked = project.copy(
            layers = listOf(
                Layer("L1", clips = listOf(music)),
                Layer("L2", clips = listOf(voice.copy(audio = AudioSettings()))),
            ),
        )
        assertTrue(
            ProjectRender.timeline(undecked, media, 360, 640).layers.flatMap {
                it.clips
            }.all { it.ducks.isEmpty() },
        )
    }
}
