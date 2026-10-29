package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.GLES20
import androidx.annotation.OptIn
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * Draws single frames of render timelines offscreen with the layer compositor, for stills such
 * as the live filter thumbnails (#25). It keeps one GL context on a thread of its own; [release]
 * it when done.
 */
@OptIn(UnstableApi::class)
class StillRenderer(private val context: Context) {
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "clipra-stills") }
    private val dispatcher = executor.asCoroutineDispatcher()
    private var display: EGLDisplay? = null
    private var eglContext: EGLContext? = null

    /** The frame of [timeline] at [timeUs]. */
    suspend fun render(timeline: RenderTimeline, timeUs: Long): Bitmap = withContext(dispatcher) {
        if (eglContext == null) {
            val newDisplay = GlUtil.getDefaultEglDisplay()
            val newContext = GlUtil.createEglContext(newDisplay)
            GlUtil.createFocusedPlaceholderEglSurface(newContext, newDisplay)
            display = newDisplay
            eglContext = newContext
        }
        draw(context, timeline, timeUs)
    }

    fun release() {
        executor.execute {
            val currentDisplay = display
            val currentContext = eglContext
            if (currentDisplay != null && currentContext != null) {
                GlUtil.destroyEglContext(currentDisplay, currentContext)
            }
            display = null
            eglContext = null
        }
        executor.shutdown()
    }

    companion object {
        /** One frame in a GL context made for this call alone, on the calling thread. */
        fun renderOnce(context: Context, timeline: RenderTimeline, timeUs: Long): Bitmap {
            val display = GlUtil.getDefaultEglDisplay()
            val eglContext = GlUtil.createEglContext(display)
            GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
            try {
                return draw(context, timeline, timeUs)
            } finally {
                GlUtil.destroyEglContext(display, eglContext)
            }
        }

        /** Draws with the GL context current on this thread. */
        private fun draw(context: Context, timeline: RenderTimeline, timeUs: Long): Bitmap {
            val width = timeline.width
            val height = timeline.height
            val program = LayerCompositor(timeline).toGlShaderProgram(context, false)
                as BaseGlShaderProgram
            val texture = GlUtil.createTexture(width, height, false)
            val fbo = GlUtil.createFboForTexture(texture)
            try {
                program.configure(16, 16)
                GlUtil.focusFramebufferUsingCurrentContext(fbo, width, height)
                program.drawFrame(0, timeUs)
                val buffer = ByteBuffer.allocateDirect(width * height * 4)
                    .order(ByteOrder.nativeOrder())
                val format = GLES20.GL_RGBA
                GLES20.glReadPixels(0, 0, width, height, format, GLES20.GL_UNSIGNED_BYTE, buffer)
                GlUtil.checkGlError()
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                buffer.rewind()
                bitmap.copyPixelsFromBuffer(buffer)
                // GL rows run bottom to top.
                val upright = Matrix().apply { postScale(1f, -1f) }
                return Bitmap.createBitmap(bitmap, 0, 0, width, height, upright, false)
            } finally {
                program.release()
                GlUtil.deleteFbo(fbo)
                GlUtil.deleteTexture(texture)
            }
        }
    }
}
