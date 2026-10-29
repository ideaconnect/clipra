package tech.idct.clipra.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ClipGeometryTest {
    private val frame = Size(1080, 1920)

    // A 16:9 video in a 9:16 frame.
    private val landscape = Size(1920, 1080)

    private fun assertPoint(expected: Point, actual: Point) {
        assertEquals("x", expected.x, actual.x, 0.01f)
        assertEquals("y", expected.y, actual.y, 0.01f)
    }

    @Test
    fun fillCoversTheFrameAndFitShowsTheWholeSource() {
        assertEquals(1920f / 1080, ClipGeometry.baseScale(Fit.Fill, frame, landscape), 1e-4f)
        assertEquals(1080f / 1920, ClipGeometry.baseScale(Fit.Fit, frame, landscape), 1e-4f)
    }

    @Test
    fun defaultTransformFillsTheFrameCentred() {
        val corners = ClipGeometry.corners(Transform(), frame, landscape)
        // 1920 x 1080 scaled to 3413.3 x 1920, centred: it spills over left and right.
        val halfWidth = 1920f * (1920f / 1080) / 2
        assertPoint(Point(540 - halfWidth, 0f), corners[0])
        assertPoint(Point(540 + halfWidth, 0f), corners[1])
        assertPoint(Point(540 - halfWidth, 1920f), corners[2])
        assertPoint(Point(540 + halfWidth, 1920f), corners[3])
    }

    @Test
    fun fitLetterboxes() {
        val corners = ClipGeometry.corners(Transform(fit = Fit.Fit), frame, landscape)
        // 1080 x 607.5, centred vertically.
        assertPoint(Point(0f, 960 - 303.75f), corners[0])
        assertPoint(Point(1080f, 960 + 303.75f), corners[3])
    }

    @Test
    fun positionAndScaleMoveFromTheCentre() {
        val transform = Transform(x = 0.25f, y = -0.1f, scale = 0.5f, fit = Fit.Fit)
        val corners = ClipGeometry.corners(transform, frame, landscape)
        // Centre at (810, 768); size 540 x 303.75.
        assertPoint(Point(810 - 270f, 768 - 151.875f), corners[0])
        assertPoint(Point(810 + 270f, 768 + 151.875f), corners[3])
    }

    @Test
    fun rotationTurnsClockwise() {
        val square = Size(100, 100)
        val transform = Transform(rotation = 90f, fit = Fit.Fit, scale = 0.5f)
        val corners = ClipGeometry.corners(transform, Size(1000, 1000), square)
        // 500 x 500 around (500, 500): turned a quarter clockwise, the source's top-left corner
        // lands at the top right.
        assertPoint(Point(750f, 250f), corners[0])
        assertPoint(Point(750f, 750f), corners[1])
        assertPoint(Point(250f, 250f), corners[2])
    }

    @Test
    fun flipMirrorsInPlace() {
        val square = Size(100, 100)
        val transform = Transform(flipHorizontal = true, fit = Fit.Fit, scale = 0.5f)
        val corners = ClipGeometry.corners(transform, Size(1000, 1000), square)
        assertPoint(Point(750f, 250f), corners[0])
        assertPoint(Point(250f, 250f), corners[1])
    }

    @Test
    fun cropTrimsEdgesWithoutMovingThePicture() {
        val square = Size(100, 100)
        val transform = Transform(fit = Fit.Fit, scale = 0.5f, cropLeft = 0.2f, cropBottom = 0.1f)
        val corners = ClipGeometry.corners(transform, Size(1000, 1000), square)
        assertPoint(Point(350f, 250f), corners[0])
        assertPoint(Point(750f, 700f), corners[3])
        val whole = ClipGeometry.uncroppedCorners(transform, Size(1000, 1000), square)
        assertPoint(Point(250f, 250f), whole[0])
    }

    @Test
    fun toSourceInvertsToFrame() {
        val transform = Transform(
            x = 0.1f,
            y = 0.2f,
            scale = 0.7f,
            rotation = 33f,
            flipHorizontal = true,
            flipVertical = true,
        )
        val point = ClipGeometry.toFrame(transform, frame, landscape, 0.3f, 0.8f)
        assertPoint(Point(0.3f, 0.8f), ClipGeometry.toSource(transform, frame, landscape, point))
    }

    @Test
    fun containsHonoursTheCrop() {
        val transform = Transform(fit = Fit.Fit, scale = 0.5f, cropLeft = 0.5f)
        val square = Size(100, 100)
        val size = Size(1000, 1000)
        assertTrue(ClipGeometry.contains(transform, size, square, Point(600f, 500f)))
        assertFalse(ClipGeometry.contains(transform, size, square, Point(400f, 500f)))
        assertFalse(ClipGeometry.contains(transform, size, square, Point(100f, 500f)))
    }

    @Test
    fun unknownSourceSizeActsLikeTheFrame() {
        val corners = ClipGeometry.corners(Transform(), frame, Size(0, 0))
        assertPoint(Point(0f, 0f), corners[0])
        assertPoint(Point(1080f, 1920f), corners[3])
    }
}
