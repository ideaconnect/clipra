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
 * The MediaPipe face detector (BlazeFace, #52) on LiteRT, at a [range]. The picture is fitted
 * into the model's square input (keeping its shape), its anchor boxes are decoded and
 * overlapping ones merged, as MediaPipe's own graph does. Runs on the CPU; nothing leaves the
 * device. One instance per thread.
 */
class FaceDetector(context: Context, private val range: Range = Range.Short) : AutoCloseable {
    /**
     * Which BlazeFace: [Short] for faces within a couple of metres (#52), [Full] for faces up to
     * five metres, as in street footage (#182). Full range finds a face about a twentieth of a
     * 16:9 frame wide in all of it, where short range needs about a sixth; it is slower.
     */
    enum class Range(
        internal val model: String,
        internal val size: Int,
        /** Cells across, and anchors per cell, of each feature map. */
        internal val layout: List<Pair<Int, Int>>,
        internal val minScore: Float,
    ) {
        Short("models/blaze_face_short_range.tflite", 128, listOf(16 to 2, 8 to 6), 0.5f),

        // MediaPipe's own threshold for this model; signs and shirts in a blurred street frame
        // scored up to 0.56, people's faces from 0.64.
        Full("models/face_detection_full_range_sparse.tflite", 192, listOf(48 to 1), 0.6f),
    }

    private val size = range.size
    private val anchors = range.layout.sumOf { (cells, per) -> cells * cells * per }
    private val anchorX = FloatArray(anchors)
    private val anchorY = FloatArray(anchors)
    private val interpreter: Interpreter
    private val input = ByteBuffer.allocateDirect(
        4 * size * size * 3,
    ).order(ByteOrder.nativeOrder())
    private val boxes = Array(1) { Array(anchors) { FloatArray(COORDS) } }
    private val scores = Array(1) { Array(anchors) { FloatArray(1) } }
    private val boxesOutput: Int
    private val square = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    private val pixels = IntArray(size * size)
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)

    init {
        var i = 0
        for ((cells, per) in range.layout) {
            for (y in 0 until cells) {
                for (x in 0 until cells) {
                    repeat(per) {
                        anchorX[i] = (x + 0.5f) / cells
                        anchorY[i] = (y + 0.5f) / cells
                        i++
                    }
                }
            }
        }
        val model = context.assets.openFd(range.model).use { fd ->
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
    fun detect(
        picture: Bitmap,
        minScore: Float = range.minScore,
        region: Rect? = null,
    ): List<Face> {
        val area = region ?: Rect(0, 0, picture.width, picture.height)
        // Fitted into the square, centred, with black around it.
        val scale = min(size.toFloat() / area.width(), size.toFloat() / area.height())
        val w = area.width() * scale
        val h = area.height() * scale
        val left = (size - w) / 2
        val top = (size - h) / 2
        square.eraseColor(Color.BLACK)
        Canvas(square).drawBitmap(picture, area, RectF(left, top, left + w, top + h), paint)
        square.getPixels(pixels, 0, size, 0, 0, size, size)
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
        for (i in 0 until anchors) {
            val score = sigmoid(scores[0][i][0].coerceIn(-CLIP, CLIP))
            if (score < minScore) continue
            val raw = boxes[0][i]
            val cx = raw[0] / size + anchorX[i]
            val cy = raw[1] / size + anchorY[i]
            val bw = raw[2] / size
            val bh = raw[3] / size
            // Back from the square to the area, in pixels, then to fractions of the picture.
            val box = RectF(
                (area.left + ((cx - bw / 2) * size - left) / scale) / picture.width,
                (area.top + ((cy - bh / 2) * size - top) / scale) / picture.height,
                (area.left + ((cx + bw / 2) * size - left) / scale) / picture.width,
                (area.top + ((cy + bh / 2) * size - top) / scale) / picture.height,
            )
            found += Face(box, score)
        }
        return merge(found)
    }

    override fun close() = interpreter.close()

    companion object {
        private const val THREADS = 2
        private const val COORDS = 16
        private const val CLIP = 100f

        /** Boxes overlapping more than this are one face (MediaPipe's setting). */
        private const val OVERLAP = 0.3f

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
