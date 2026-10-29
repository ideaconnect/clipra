package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.idct.clipra.core.model.BlendMode
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.FrameRate
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.TextSpec
import tech.idct.clipra.core.model.edit.LayerTarget
import tech.idct.clipra.core.model.edit.MoveClip

class ProjectRenderTest {
    private val video =
        MediaAsset("v", "content://media/1", MediaKind.Video, durationUs = 10_000_000)
    private val music =
        MediaAsset("m", "content://media/2", MediaKind.Audio, durationUs = 60_000_000)
    private val missing = MediaAsset("gone", "content://media/3", MediaKind.Video)

    private val resolver = object : MediaResolver {
        override fun source(asset: MediaAsset): String? = if (asset.id ==
            "gone"
        ) {
            null
        } else {
            "/proxies/${asset.id}.mp4"
        }

        override fun image(asset: MediaAsset): Bitmap? = null
    }

    private fun videoClip(id: String, start: Long, mediaId: String = "v") = Clip(
        id = id,
        type = ClipType.Video,
        startUs = start,
        durationUs = 2_000_000,
        source = MediaRef(mediaId, inPointUs = 500_000, outPointUs = 2_500_000),
    )

    private val project = Project(
        id = "p",
        name = "Render",
        format = Format(resolution = Resolution.HD, frameRate = FrameRate.Fps25),
        media = listOf(video, music, missing),
        layers = listOf(
            Layer("L1", clips = listOf(videoClip("a", 0), videoClip("lost", 3_000_000, "gone"))),
            Layer(
                "L2",
                hidden = true,
                clips = listOf(videoClip("b", 1_000_000).copy(blend = BlendMode.Screen)),
            ),
            Layer(
                "L3",
                muted = true,
                clips = listOf(
                    Clip(
                        "music",
                        ClipType.Audio,
                        startUs = 0,
                        durationUs = 8_000_000,
                        source = MediaRef("m", outPointUs = 8_000_000),
                    ),
                    Clip(
                        "title",
                        ClipType.Text,
                        startUs = 0,
                        durationUs = 1_000_000,
                        text = TextSpec("Hi"),
                    ),
                ),
            ),
        ),
    )

    private val timeline = ProjectRender.timeline(project, resolver)

    @Test
    fun formatAndOrder() {
        assertEquals(720, timeline.width)
        assertEquals(1280, timeline.height)
        assertEquals(25, timeline.frameRate)
        assertEquals(8_000_000L, timeline.durationUs)
        assertEquals(3, timeline.layers.size)
    }

    @Test
    fun hiddenAndMutedLayersAreMarked() {
        assertEquals(listOf(false, true, false), timeline.layers.map { it.hidden })
        assertEquals(listOf(false, false, true), timeline.layers.map { it.muted })
    }

    @Test
    fun clipsCarryTheirSourceTimingAndBlend() {
        val a = timeline.layers[0].clips.single()
        assertEquals("a", a.id)
        assertEquals(RenderSource.Video("/proxies/v.mp4", 500_000), a.source)
        assertEquals(BlendMode.Screen, timeline.layers[1].clips.single().blend)
        assertEquals(
            RenderSource.Audio("/proxies/m.mp4", 0),
            timeline.layers[2].clips.single().source,
        )
    }

    @Test
    fun missingMediaAndClipsWithoutPicturesYetAreLeftOut() {
        assertTrue(timeline.layers[0].clips.none { it.id == "lost" })
        assertTrue(timeline.layers[2].clips.none { it.id == "title" })
    }

    @Test
    fun aClipMovedToAHigherLayerIsDrawnAboveTheLowerOnes() {
        val moved = MoveClip(
            "a",
            startUs = 9_000_000,
            target = LayerTarget.Existing("L3"),
        ).apply(project)
        val layers = ProjectRender.timeline(moved, resolver).layers
        assertTrue(layers[0].clips.none { it.id == "a" })
        assertTrue(layers[2].clips.any { it.id == "a" })
    }
}
