package tech.idct.clipra.ml.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import org.tensorflow.lite.Interpreter
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/** A face found in a picture: its box (fractions of the picture, from the top-left) and score. */
data class Face(val box: RectF, val score: Float)

/**
 * The MediaPipe face detector (BlazeFace short range, #52) on LiteRT: faces within a couple of
 * metres of the camera. The picture is fitted into the model's 128 × 128 input (keeping its
 * shape), its 896 anchor boxes are decoded and overlapping ones merged, as MediaPipe's own
 * graph does. Runs on the CPU; nothing leaves the device. One instance per thread.
 */
class FaceDetector(context: Context) : AutoCloseable {
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(
        4 * SIZE * SIZE * 3,
    ).order(ByteOrder.nativeOrder())
    private val boxes = Array(1) { Array(ANCHORS) { FloatArray(COORDS) } }
    private val scores = Array(1) { Array(ANCHORS) { FloatArray(1) } }
    private val boxesOutput: Int
    private val square = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(SIZE * SIZE)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        val model = context.assets.openFd(MODEL).use { fd ->
            FileInputStream(fd.fileDescriptor).channel.use { channel ->
                channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength)
            }
        }
        interpreter = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
        // The box regressors are the output with 16 values per anchor.
        boxesOutput = (0 until interpreter.outputTensorCount).first {
            interpreter.getOutputTensor(it).shape().last() == COORDS
        }
    }

    /**
     * The faces in [picture], most likely first; none scores under [minScore]. With a [region]
     * (pixels of [picture]) only that part is looked at, so faces too small to find in the whole
     * picture are big enough (#182); the boxes are still fractions of the whole picture.
     */
    fun detect(picture: Bitmap, minScore: Float = MIN_SCORE, region: Rect? = null): List<Face> {
        val area = region ?: Rect(0, 0, picture.width, picture.height)
        // Fitted into the square, centred, with black around it.
        val scale = min(SIZE.toFloat() / area.width(), SIZE.toFloat() / area.height())
        val w = area.width() * scale
        val h = area.height() * scale
        val left = (SIZE - w) / 2
        val top = (SIZE - h) / 2
        square.eraseColor(Color.BLACK)
        Canvas(square).drawBitmap(picture, area, RectF(left, top, left + w, top + h), paint)
        square.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
        input.rewind()
        for (pixel in pixels) {
            input.putFloat((pixel shr 16 and 0xFF) / 127.5f - 1f)
            input.putFloat((pixel shr 8 and 0xFF) / 127.5f - 1f)
            input.putFloat((pixel and 0xFF) / 127.5f - 1f)
        }
        input.rewind()
        val outputs = HashMap<Int, Any>()
        outputs[boxesOutput] = boxes
        outputs[1 - boxesOutput] = scores
        interpreter.runForMultipleInputsOutputs(arrayOf(input), outputs)

        val found = ArrayList<Face>()
        for (i in 0 until ANCHORS) {
            val score = sigmoid(scores[0][i][0].coerceIn(-CLIP, CLIP))
            if (score < minScore) continue
            val raw = boxes[0][i]
            val cx = raw[0] / SIZE + ANCHOR_X[i]
            val cy = raw[1] / SIZE + ANCHOR_Y[i]
            val bw = raw[2] / SIZE
            val bh = raw[3] / SIZE
            // Back from the square to the area, in pixels, then to fractions of the picture.
            val box = RectF(
                (area.left + ((cx - bw / 2) * SIZE - left) / scale) / picture.width,
                (area.top + ((cy - bh / 2) * SIZE - top) / scale) / picture.height,
                (area.left + ((cx + bw / 2) * SIZE - left) / scale) / picture.width,
                (area.top + ((cy + bh / 2) * SIZE - top) / scale) / picture.height,
            )
            found += Face(box, score)
        }
        return merge(found)
    }

    override fun close() = interpreter.close()

    companion object {
        /** The model's input side. */
        const val SIZE = 128

        private const val MODEL = "models/blaze_face_short_range.tflite"
        private const val THREADS = 2
        private const val COORDS = 16
        private const val CLIP = 100f
        private const val MIN_SCORE = 0.5f

        /** Boxes overlapping more than this are one face (MediaPipe's setting). */
        private const val OVERLAP = 0.3f

        /** 16 × 16 cells with 2 anchors (stride 8), then 8 × 8 cells with 6 (stride 16). */
        private val LAYOUT = listOf(16 to 2, 8 to 6)
        val ANCHORS = LAYOUT.sumOf { (cells, per) -> cells * cells * per }
        private val ANCHOR_X = FloatArray(ANCHORS)
        private val ANCHOR_Y = FloatArray(ANCHORS)

        init {
            var i = 0
            for ((cells, per) in LAYOUT) {
                for (y in 0 until cells) {
                    for (x in 0 until cells) {
                        repeat(per) {
                            ANCHOR_X[i] = (x + 0.5f) / cells
                            ANCHOR_Y[i] = (y + 0.5f) / cells
                            i++
                        }
                    }
                }
            }
        }

        private fun sigmoid(x: Float) = 1f / (1f + exp(-x))

        /**
         * Overlapping boxes as one face each: the best box of a group, moved to the
         * score-weighted mean of the group (MediaPipe's weighted non-maximum suppression).
         */
        fun merge(faces: List<Face>): List<Face> {
            val left = faces.sortedByDescending { it.score }.toMutableList()
            val merged = ArrayList<Face>()
            while (left.isNotEmpty()) {
                val best = left.first()
                val group = left.filter { overlap(it.box, best.box) > OVERLAP }
                left.removeAll(group)
                val total = group.sumOf { it.score.toDouble() }.toFloat()
                val box = RectF(
                    group.sumOf { (it.box.left * it.score).toDouble() }.toFloat() / total,
                    group.sumOf { (it.box.top * it.score).toDouble() }.toFloat() / total,
                    group.sumOf { (it.box.right * it.score).toDouble() }.toFloat() / total,
                    group.sumOf { (it.box.bottom * it.score).toDouble() }.toFloat() / total,
                )
                merged += Face(box, best.score)
            }
            return merged
        }

        /** Intersection over union. */
        private fun overlap(a: RectF, b: RectF): Float {
            val w = max(0f, min(a.right, b.right) - max(a.left, b.left))
            val h = max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val shared = w * h
            val union = a.width() * a.height() + b.width() * b.height() - shared
            return if (union <= 0f) 0f else shared / union
        }
    }
}
