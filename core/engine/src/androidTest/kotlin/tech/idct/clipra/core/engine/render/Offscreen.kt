package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/** Renders compositor frames straight into a framebuffer, for golden-image tests. */
@OptIn(UnstableApi::class)
object Offscreen {
    /** The frame of [timeline] at [timeUs], drawn by the compositor in its own GL context. */
    fun render(context: Context, timeline: RenderTimeline, timeUs: Long): Bitmap {
        val width = timeline.width
        val height = timeline.height
        val display = GlUtil.getDefaultEglDisplay()
        val eglContext = GlUtil.createEglContext(display)
        GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        try {
            val program = LayerCompositor(timeline).toGlShaderProgram(context, false)
                as BaseGlShaderProgram
            program.configure(16, 16)
            val texture = GlUtil.createTexture(width, height, false)
            val fbo = GlUtil.createFboForTexture(texture)
            GlUtil.focusFramebufferUsingCurrentContext(fbo, width, height)
            program.drawFrame(0, timeUs)
            val buffer = ByteBuffer.allocateDirect(width * height * 4)
                .order(ByteOrder.nativeOrder())
            val format = GLES20.GL_RGBA
            GLES20.glReadPixels(0, 0, width, height, format, GLES20.GL_UNSIGNED_BYTE, buffer)
            GlUtil.checkGlError()
            program.release()
            GlUtil.deleteFbo(fbo)
            GlUtil.deleteTexture(texture)
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            buffer.rewind()
            bitmap.copyPixelsFromBuffer(buffer)
            // GL rows run bottom to top.
            val upright = Matrix().apply { postScale(1f, -1f) }
            return Bitmap.createBitmap(bitmap, 0, 0, width, height, upright, false)
        } finally {
            GlUtil.destroyEglContext(display, eglContext)
        }
    }

    /** The share of pixels within [tolerance] on every channel. */
    fun match(expected: Bitmap, actual: Bitmap, tolerance: Int): Double {
        val count = expected.width * expected.height
        val a = IntArray(count).also {
            expected.getPixels(it, 0, expected.width, 0, 0, expected.width, expected.height)
        }
        val b = IntArray(count).also {
            actual.getPixels(it, 0, actual.width, 0, 0, actual.width, actual.height)
        }
        var close = 0
        for (i in 0 until count) {
            val near = abs(Color.red(a[i]) - Color.red(b[i])) <= tolerance &&
                abs(Color.green(a[i]) - Color.green(b[i])) <= tolerance &&
                abs(Color.blue(a[i]) - Color.blue(b[i])) <= tolerance
            if (near) close++
        }
        return close.toDouble() / count
    }
}
