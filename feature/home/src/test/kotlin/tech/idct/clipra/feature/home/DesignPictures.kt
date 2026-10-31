package tech.idct.clipra.feature.home

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection

/**
 * Stand-ins for the photos in the design's project frames (Main and TabletHome boards):
 * gradients in their colours, top to bottom, by name.
 */
internal object DesignPictures : ProjectPictures {
    private val gradients = mapOf(
        "sunset" to (Color(0xFF5E4B8B) to Color(0xFFE8946A)),
        "teaser" to (Color(0xFF24565C) to Color(0xFF0C1C20)),
        "run" to (Color(0xFFBFE3EE) to Color(0xFF5E9B45)),
        "brown" to (Color(0xFFC79A6E) to Color(0xFF6E4A32)),
        "blue" to (Color(0xFF3A78B5) to Color(0xFF1C3D63)),
        "coral" to (Color(0xFFE07A6A) to Color(0xFF8A5A8E)),
        "green" to (Color(0xFF9BD07A) to Color(0xFF4B8A3C)),
        "studio" to (Color(0xFF3B2E4F) to Color(0xFF15121C)),
        "hills" to (Color(0xFF9CC3E0) to Color(0xFF3F6B4A)),
        "nata" to (Color(0xFFF2C46B) to Color(0xFF9A5B2A)),
    )

    /** The 2×2 mosaic's four cells. */
    val tiles = listOf("brown", "blue", "coral", "green")

    override suspend fun picture(mediaId: String): ImageBitmap? {
        val (top, bottom) = gradients[mediaId] ?: return null
        val bitmap = ImageBitmap(SIDE, SIDE)
        val size = Size(SIDE.toFloat(), SIDE.toFloat())
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), size) {
            drawRect(Brush.verticalGradient(listOf(top, bottom)))
        }
        return bitmap
    }

    private const val SIDE = 160
}
