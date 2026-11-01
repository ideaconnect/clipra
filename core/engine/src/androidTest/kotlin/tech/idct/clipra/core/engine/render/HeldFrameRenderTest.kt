package tech.idct.clipra.core.engine.render

import android.graphics.Color
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import tech.idct.clipra.core.model.Easing
import java.io.File
import java.nio.ByteBuffer

/**
 * Video frames a clip holds while another clip plays stay its own. A dissolve between two whole
 * clips holds both on their edge frames: the incoming one on its first frame before the cut,
 * the outgoing one on its last after it. Read straight from the decoders' textures, a held frame
 * showed the playing clip's picture on the emulator, and the dissolve came out as a hard cut.
 */
@OptIn(UnstableApi::class)
@RunWith(AndroidJUnit4::class)
class HeldFrameRenderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private lateinit var red: File
    private lateinit var blue: File

    @Before
    fun copyMedia() {
        red = copy("red")
        blue = copy("blue")
    }

    @Test
    fun aDissolveAcrossWholeClipsMixesBoth() {
        // Red (4 s) then blue (3 s), a linear dissolve from 3.5 to 4.5 s, drawn frame after
        // frame as playback and export draw them.
        val times = (0..30).map { 3_500_000L + it * 1_000_000L / 30 }
        val colours = centres(times).let { times.zip(it).toMap() }

        // A quarter of the way in, red plays and blue holds its first frame.
        val before = colours.getValue(times[8])
        assertTrue("before the cut: ${hex(before)}", Color.red(before) > 150)
        assertTrue("before the cut: ${hex(before)}", Color.blue(before) in 40..110)

        // Three quarters of the way, blue plays and red holds its last frame.
        val after = colours.getValue(times[22])
        assertTrue("after the cut: ${hex(after)}", Color.blue(after) > 150)
        assertTrue("after the cut: ${hex(after)}", Color.red(after) in 40..110)
    }

    private fun timeline() = RenderTimeline(
        width = WIDTH,
        height = HEIGHT,
        frameRate = 30,
        durationUs = 7_000_000,
        background = Color.BLACK,
        layers = listOf(
            RenderLayer(
                clips = listOf(
                    RenderClip("red", 0, 4_000_000, RenderSource.Video(red.path, 0, 4_000_000)),
                    RenderClip(
                        "blue",
                        4_000_000,
                        3_000_000,
                        RenderSource.Video(blue.path, 0, 3_000_000),
                    ),
                ),
                transitions = listOf(
                    RenderTransition(
                        "red",
                        "blue",
                        startUs = 3_500_000,
                        durationUs = 1_000_000,
                        type = "dissolve",
                        easing = Easing.Linear,
                    ),
                ),
            ),
        ),
    )

    /** The centre colour of each of [times], drawn in order by one compositor. */
    private fun centres(times: List<Long>): List<Int> {
        val display = GlUtil.getDefaultEglDisplay()
        val eglContext = GlUtil.createEglContext(display)
        GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        val program = LayerCompositor(timeline()).toGlShaderProgram(context, false)
            as BaseGlShaderProgram
        val texture = GlUtil.createTexture(WIDTH, HEIGHT, false)
        val fbo = GlUtil.createFboForTexture(texture)
        try {
            program.configure(16, 16)
            return times.map { timeUs ->
                GlUtil.focusFramebufferUsingCurrentContext(fbo, WIDTH, HEIGHT)
                program.drawFrame(0, timeUs)
                val pixel = ByteBuffer.allocateDirect(4)
                GLES20.glReadPixels(
                    WIDTH / 2,
                    HEIGHT / 2,
                    1,
                    1,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    pixel,
                )
                Color.rgb(
                    pixel.get(0).toInt() and 0xFF,
                    pixel.get(1).toInt() and 0xFF,
                    pixel.get(2).toInt() and 0xFF,
                )
            }
        } finally {
            program.release()
            GlUtil.deleteFbo(fbo)
            GlUtil.deleteTexture(texture)
            GlUtil.destroyEglContext(display, eglContext)
        }
    }

    private fun copy(name: String): File = File(context.cacheDir, "held-$name.mp4").also { file ->
        instrumentation.context.assets.open("spike/$name.mp4").use { input ->
            file.outputStream().use { input.copyTo(it) }
        }
    }

    private fun hex(colour: Int) = "#%06X".format(colour and 0xFFFFFF)

    private companion object {
        const val WIDTH = 320
        const val HEIGHT = 180
    }
}
