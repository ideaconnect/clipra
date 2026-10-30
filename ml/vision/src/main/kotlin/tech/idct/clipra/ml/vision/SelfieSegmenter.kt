package tech.idct.clipra.ml.vision

import android.content.Context
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel

/**
 * The MediaPipe selfie segmenter (#50) on LiteRT: for a [SIZE] × [SIZE] picture, each pixel's
 * confidence (0 to 1) that it shows a person. The model ships in the APK and runs on the CPU;
 * nothing leaves the device. One instance per thread.
 */
class SelfieSegmenter(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(
        4 * SIZE * SIZE * 3,
    ).order(ByteOrder.nativeOrder())
    private val output = ByteBuffer.allocateDirect(4 * SIZE * SIZE).order(ByteOrder.nativeOrder())

    init {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
    }

    /** The model's input and output shapes, for checks. */
    val inputShape: IntArray get() = interpreter.getInputTensor(0).shape()
    val outputShape: IntArray get() = interpreter.getOutputTensor(0).shape()

    /**
     * Finds the people in [pixels] (ARGB, [SIZE] × [SIZE], top row first) and writes each
     * pixel's confidence into [out], in the same layout.
     */
    fun segment(pixels: IntArray, out: FloatArray) {
        require(pixels.size == SIZE * SIZE && out.size == SIZE * SIZE)
        input.rewind()
        for (pixel in pixels) {
            input.putFloat((pixel shr 16 and 0xFF) / 255f)
            input.putFloat((pixel shr 8 and 0xFF) / 255f)
            input.putFloat((pixel and 0xFF) / 255f)
        }
        input.rewind()
        output.rewind()
        interpreter.run(input, output)
        output.rewind()
        output.asFloatBuffer().get(out)
    }

    override fun close() = interpreter.close()

    companion object {
        /** The model's input and output side. */
        const val SIZE = 256

        private const val MODEL = "models/selfie_segmenter.tflite"
        private const val THREADS = 4
    }
}
