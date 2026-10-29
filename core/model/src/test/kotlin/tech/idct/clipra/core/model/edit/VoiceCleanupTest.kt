package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.VoiceCleanup
import tech.idct.clipra.core.model.VoiceEngine

/** AI voice cleanup settings (#39): strength limits, the noise mixed back and "apply to all". */
class VoiceCleanupTest {
    private fun clip(id: String, media: String, type: ClipType = ClipType.Video) = Clip(
        id = id,
        type = type,
        startUs = 0,
        durationUs = 1_000_000,
        source = MediaRef(media, 0, 1_000_000),
    )

    private val project = Project(
        id = "p",
        name = "p",
        media = listOf(
            MediaAsset("talk", "content://1", MediaKind.Video, hasAudio = true),
            MediaAsset("silent", "content://2", MediaKind.Video, hasAudio = false),
            MediaAsset("song", "content://3", MediaKind.Audio, hasAudio = true),
        ),
        layers = listOf(
            Layer("L1", clips = listOf(clip("a", "talk"), clip("b", "silent"))),
            Layer("L2", clips = listOf(clip("c", "song", ClipType.Audio))),
            Layer("L3", locked = true, clips = listOf(clip("d", "talk"))),
        ),
    )

    @Test
    fun strengthStaysBetweenZeroAndOne() {
        val edited = SetAudio("a", AudioSettings(voiceCleanup = VoiceCleanup(strength = 1.6f)))
            .apply(project)
        assertEquals(1f, edited.findClip("a")!!.second.audio.voiceCleanup!!.strength)
    }

    @Test
    fun lowerStrengthsMixBackMoreOfTheRecording() {
        assertEquals(0f, VoiceCleanup(strength = 1f).noisyShare)
        assertEquals(1f, VoiceCleanup(strength = 0f).noisyShare)
        // 70%: noise is held 28 dB down.
        assertEquals(0.0398f, VoiceCleanup(strength = 0.7f).noisyShare, 0.0001f)
        assertEquals(70, VoiceCleanup(strength = 0.7f).percent)
    }

    @Test
    fun applyToAllReachesEveryVideoWithSoundOnUnlockedLayers() {
        val cleanup = VoiceCleanup(VoiceEngine.RNNoise, 0.5f)
        val edited = ApplyVoiceCleanupToAll(cleanup).apply(project)
        fun of(id: String) = edited.findClip(id)!!.second.audio.voiceCleanup
        assertEquals(cleanup, of("a"))
        assertNull(of("b"))
        assertNull(of("c"))
        assertNull(of("d"))
        assertNull(
            ApplyVoiceCleanupToAll(null).apply(edited).findClip("a")!!.second.audio.voiceCleanup,
        )
    }
}
