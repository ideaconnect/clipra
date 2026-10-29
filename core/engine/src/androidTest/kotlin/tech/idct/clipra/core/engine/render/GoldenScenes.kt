package tech.idct.clipra.core.engine.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader

/** The design's two demo pictures (design/canvas/Filters.dc.html, Transitions.dc.html). */
object GoldenScenes {
    /** The design's preview scene: a sunset sky, the sun, two hills and a person. */
    fun sunset(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val w = width.toFloat()
        val h = height.toFloat()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            h,
            intArrayOf(
                0xFF23305C.toInt(),
                0xFF6B4D7A.toInt(),
                0xFFD9785A.toInt(),
                0xFFF3B064.toInt(),
            ),
            floatArrayOf(0f, 0.36f, 0.66f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, paint)
        paint.shader = null
        fun oval(left: Float, top: Float, right: Float, bottom: Float, color: Long) {
            paint.color = color.toInt()
            canvas.drawOval(RectF(left * w, top * h, right * w, bottom * h), paint)
        }
        val sun = 0.24f * w
        paint.color = 0xFFFFE6A8.toInt()
        canvas.drawOval(RectF(0.62f * w, 0.30f * h, 0.62f * w + sun, 0.30f * h + sun), paint)
        oval(-0.30f, 0.64f, 0.65f, 1.14f, 0xFF4A3355)
        oval(0.25f, 0.72f, 1.40f, 1.22f, 0xFF22192E)
        paint.color = 0xFF2F4A7A.toInt()
        canvas.drawRoundRect(
            RectF(0.21f * w, 0.74f * h, 0.79f * w, 1.06f * h),
            0.28f * w,
            0.15f * h,
            paint,
        )
        paint.color = 0xFFB97A5B.toInt()
        canvas.drawRect(0.45f * w, 0.66f * h, 0.55f * w, 0.76f * h, paint)
        oval(0.37f, 0.50f, 0.63f, 0.68f, 0xFFC98B6B)
        paint.color = 0xFF2B1E1A.toInt()
        canvas.drawRoundRect(
            RectF(0.36f * w, 0.485f * h, 0.64f * w, 0.57f * h),
            0.14f * w,
            0.04f * h,
            paint,
        )
        return bitmap
    }

    /** The incoming clip of the Transitions board: blue sky, a yellow tram, dark street. */
    fun tram(width: Int, height: Int): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val paint = Paint()
        paint.shader = LinearGradient(
            0f,
            0f,
            0f,
            height.toFloat(),
            intArrayOf(
                0xFF16324F.toInt(),
                0xFF2F6FA8.toInt(),
                0xFFF2B705.toInt(),
                0xFFF2B705.toInt(),
                0xFF1C2635.toInt(),
                0xFF1C2635.toInt(),
            ),
            floatArrayOf(0f, 0.5f, 0.52f, 0.72f, 0.74f, 1f),
            Shader.TileMode.CLAMP,
        )
        Canvas(bitmap).drawRect(0f, 0f, width.toFloat(), height.toFloat(), paint)
        return bitmap
    }
}
