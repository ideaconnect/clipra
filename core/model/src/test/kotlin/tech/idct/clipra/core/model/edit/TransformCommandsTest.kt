package tech.idct.clipra.core.model.edit

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import tech.idct.clipra.core.model.AudioSettings
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Keyframe
import tech.idct.clipra.core.model.Keyframed
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Transform

class TransformCommandsTest {
    private val clip = Clip(
        id = "c",
        type = ClipType.Video,
        startUs = 0,
        durationUs = 1_000_000,
        source = MediaRef("m", 0, 1_000_000),
        opacity = Keyframed(1f, listOf(Keyframe(0, 0.5f))),
    )
    private val project =
        Project(id = "p", name = "p", layers = listOf(Layer("L1", clips = listOf(clip))))

    @Test
    fun setTransformReplacesTheValue() {
        val transform = Transform(x = 0.1f, scale = 0.5f, rotation = 45f, flipHorizontal = true)
        val edited = SetTransform("c", transform).apply(project)
        assertEquals(transform, edited.findClip("c")!!.second.transform.value)
    }

    @Test
    fun setBlendKeepsKeyframesAndClampsOpacity() {
        val edited = SetBlend("c", BlendMode.Screen, 1.4f).apply(project)
        val result = edited.findClip("c")!!.second
        assertEquals(BlendMode.Screen, result.blend)
        assertEquals(1f, result.opacity.value)
        assertEquals(1, result.opacity.keyframes.size)
    }

    @Test
    fun lockedLayersRefuse() {
        val locked = project.copy(layers = listOf(project.layers[0].copy(locked = true)))
        val error = assertThrows(EditRejected::class.java) {
            SetTransform("c", Transform(scale = 2f)).apply(locked)
        }
        assertEquals(EditRejected.Reason.LayerLocked, error.reason)
        assertThrows(EditRejected::class.java) {
            SetBlend("c", BlendMode.Multiply, 0.5f).apply(locked)
        }
    }

    @Test
    fun unknownClipsRefuse() {
        val error = assertThrows(EditRejected::class.java) {
            SetTransform("nope", Transform()).apply(project)
        }
        assertEquals(EditRejected.Reason.ClipNotFound, error.reason)
    }

    @Test
    fun audioStaysWithinTheClip() {
        val loud = SetAudio(
            "c",
            AudioSettings(volume = 3f, fadeInUs = 5_000_000, fadeOutUs = 200_000),
        )
            .apply(project)
        val audio = loud.findClip("c")!!.second.audio
        assertEquals(2f, audio.volume)
        assertEquals(1_000_000L, audio.fadeInUs)
        assertEquals(200_000L, audio.fadeOutUs)
    }
}
