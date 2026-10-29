package tech.idct.clipra.feature.effects.common

import android.graphics.Bitmap

/** Thumbnails come five by five from one offscreen frame. */
internal const val THUMBNAIL_GRID = 5

/** [bitmap] cut to [width] × [height]'s shape around its centre, and scaled to it. */
internal fun centreCrop(bitmap: Bitmap, width: Int, height: Int): Bitmap {
    val target = width.toFloat() / height
    val cropWidth = minOf(bitmap.width, (bitmap.height * target).toInt()).coerceAtLeast(1)
    val cropHeight = minOf(bitmap.height, (bitmap.width / target).toInt()).coerceAtLeast(1)
    val cropped = Bitmap.createBitmap(
        bitmap,
        (bitmap.width - cropWidth) / 2,
        (bitmap.height - cropHeight) / 2,
        cropWidth,
        cropHeight,
    )
    return Bitmap.createScaledBitmap(cropped, width, height, true)
}

/** Cell [index] of a [THUMBNAIL_GRID] × [THUMBNAIL_GRID] grid of [width] × [height] cells. */
internal fun gridCell(grid: Bitmap, index: Int, width: Int, height: Int): Bitmap {
    val column = index % THUMBNAIL_GRID
    val row = index / THUMBNAIL_GRID
    return Bitmap.createBitmap(grid, column * width, row * height, width, height)
}
