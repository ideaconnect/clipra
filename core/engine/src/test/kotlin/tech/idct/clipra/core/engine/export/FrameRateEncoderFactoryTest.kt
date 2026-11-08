package tech.idct.clipra.core.engine.export

import android.media.metrics.LogSessionId
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.transformer.Codec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class FrameRateEncoderFactoryTest {
    /** The video format Media3 asks for when the export's canvas is an image: no frame rate. */
    private val canvasVideo = Format.Builder()
        .setSampleMimeType(MimeTypes.VIDEO_H264)
        .setWidth(1080)
        .setHeight(1920)
        .setColorInfo(ColorInfo.SDR_BT709_LIMITED)
        .build()

    @Test
    fun theVideoEncoderGetsTheExportsFrameRate() {
        assertEquals(Format.NO_VALUE.toFloat(), canvasVideo.frameRate)
        for (fps in listOf(24f, 25f, 30f, 50f, 60f)) {
            val delegate = RecordingFactory()

            val codec = FrameRateEncoderFactory(delegate, fps).createForVideoEncoding(
                canvasVideo,
                null,
            )

            assertSame(RecordingFactory.CODEC, codec)
            val asked = delegate.video.single()
            assertEquals(fps, asked.frameRate)
            // Nothing else changes.
            assertEquals(canvasVideo.buildUpon().setFrameRate(fps).build(), asked)
        }
    }

    @Test
    fun aFrameRateTheFormatAlreadyHasGivesWayToTheExports() {
        val delegate = RecordingFactory()

        FrameRateEncoderFactory(delegate, 60f).createForVideoEncoding(
            canvasVideo.buildUpon().setFrameRate(30f).build(),
            null,
        )

        assertEquals(60f, delegate.video.single().frameRate)
    }

    @Test
    fun theSoundPassesThroughUnchanged() {
        val audio = Format.Builder()
            .setSampleMimeType(MimeTypes.AUDIO_AAC)
            .setSampleRate(48_000)
            .setChannelCount(2)
            .build()
        val delegate = RecordingFactory()

        val codec = FrameRateEncoderFactory(delegate, 60f).createForAudioEncoding(audio, null)

        assertSame(RecordingFactory.CODEC, codec)
        assertSame(audio, delegate.audio.single())
        assertTrue(delegate.video.isEmpty())
    }

    @Test
    fun theDelegateStillDecidesWhatNeedsEncodingAndWhatIsSupported() {
        val delegate = RecordingFactory()
        val factory = FrameRateEncoderFactory(delegate, 50f)

        // Media3's defaults are false and true; the delegate's answers are the opposite.
        assertTrue(factory.audioNeedsEncoding())
        assertTrue(factory.videoNeedsEncoding())
        assertFalse(factory.isVideoFormatSupported(canvasVideo))
        assertEquals(50f, delegate.checked.single().frameRate)
    }

    private class RecordingFactory : Codec.EncoderFactory {
        val video = mutableListOf<Format>()
        val audio = mutableListOf<Format>()
        val checked = mutableListOf<Format>()

        override fun createForAudioEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            audio += format
            return CODEC
        }

        override fun createForVideoEncoding(format: Format, logSessionId: LogSessionId?): Codec {
            video += format
            return CODEC
        }

        override fun isVideoFormatSupported(format: Format): Boolean {
            checked += format
            return false
        }

        override fun audioNeedsEncoding(): Boolean = true

        override fun videoNeedsEncoding(): Boolean = true

        companion object {
            /** A codec that is never used, only handed back. */
            val CODEC = Proxy.newProxyInstance(
                Codec::class.java.classLoader,
                arrayOf(Codec::class.java),
            ) { _, method, _ -> if (method.name == "toString") "codec" else null } as Codec
        }
    }
}
