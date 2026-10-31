package tech.idct.clipra.core.engine.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import tech.idct.clipra.core.fonts.R
import tech.idct.clipra.core.model.Animations
import tech.idct.clipra.core.model.TextAlign
import tech.idct.clipra.core.model.TextFonts
import tech.idct.clipra.core.model.TextSpec

/**
 * Draws text clips (#31) into bitmaps as large as the frame they render into, so text is as
 * sharp as the output, 4K included. A text card fills the frame with its colour; text over
 * video is transparent around the letters. The text is centred, wrapped to the title-safe
 * width, at [TextSpec.size] of the frame's height, in its text effect (#32).
 */
object TextRenderer {
    /** Title-safe width: text wraps within 90% of the frame. */
    const val SAFE_WIDTH = 0.9f

    /** The families Clipra ships (OFL), by the names text clips use. */
    val families = TextFonts.all

    /** [spec] drawn at [width] × [height], its letters at [motion] of their animation (#33). */
    fun render(
        context: Context,
        spec: TextSpec,
        card: Boolean,
        width: Int,
        height: Int,
        motion: Animations.TextMotion? = null,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        if (card) canvas.drawColor((spec.background ?: DEFAULT_CARD).toInt())
        if (spec.text.isBlank()) return bitmap
        val paint = paint(context, spec, height)
        val wrap = wrapWidth(width)
        val layout = layout(spec, paint, wrap)
        canvas.translate((width - wrap) / 2f, (height - layout.height) / 2f)
        val effect = TextEffectPainter(layout, paint, spec, card)
        if (motion == null) {
            effect.draw(canvas)
        } else {
            TextMotionPainter(
                layout,
                paint.textSize,
                width,
                height,
                effect::draw,
            ).draw(canvas, motion)
        }
        return bitmap
    }

    /**
     * Where [spec]'s words show in a [width] × [height] frame, in fractions of it, before the
     * clip's transform: what Auto-reframe keeps inside a new frame (#53). Null without words.
     */
    fun box(context: Context, spec: TextSpec, width: Int, height: Int): RectF? {
        if (spec.text.isBlank()) return null
        val wrap = wrapWidth(width)
        val layout = layout(spec, paint(context, spec, height), wrap)
        val left = (width - wrap) / 2f
        val top = (height - layout.height) / 2f
        val lines = 0 until layout.lineCount
        return RectF(
            (left + lines.minOf { layout.getLineLeft(it) }) / width,
            top / height,
            (left + lines.maxOf { layout.getLineRight(it) }) / width,
            (top + layout.height) / height,
        )
    }

    private fun paint(context: Context, spec: TextSpec, height: Int) =
        TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            typeface = typeface(context, spec.font)
            textSize = spec.size * height
            color = spec.color.toInt()
            fontVariationSettings = variation(spec.font, spec.weight)
        }

    private fun wrapWidth(width: Int) = (width * SAFE_WIDTH).toInt().coerceAtLeast(1)

    private fun layout(spec: TextSpec, paint: TextPaint, wrap: Int): StaticLayout =
        StaticLayout.Builder.obtain(spec.text, 0, spec.text.length, paint, wrap)
            .setAlignment(alignment(spec.align))
            .setIncludePad(false)
            .setLineSpacing(0f, LINE_SPACING)
            .build()

    /** The bundled typeface of [family]; unknown names fall back to Geist. */
    fun typeface(context: Context, family: String): Typeface = context.resources.getFont(
        when (family) {
            BRICOLAGE -> R.font.bricolage_grotesque
            GEIST_MONO -> R.font.geist_mono
            else -> R.font.geist
        },
    )

    /** Variable-font axes: the weight, and Bricolage's optical size for display text. */
    internal fun variation(family: String, weight: Int): String {
        val wght = "'wght' ${weight.coerceIn(100, 900)}"
        return if (family == BRICOLAGE) "$wght, 'opsz' 48" else wght
    }

    private fun alignment(align: TextAlign) = when (align) {
        TextAlign.Start -> Layout.Alignment.ALIGN_NORMAL
        TextAlign.Center -> Layout.Alignment.ALIGN_CENTER
        TextAlign.End -> Layout.Alignment.ALIGN_OPPOSITE
    }

    const val BRICOLAGE = TextFonts.BRICOLAGE
    const val GEIST = TextFonts.GEIST
    const val GEIST_MONO = TextFonts.GEIST_MONO

    private const val DEFAULT_CARD = 0xFF000000
    private const val LINE_SPACING = 1.05f
}
