package tech.idct.clipra.core.engine.render

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The blank H.264 stream of the preview's canvas clips (#227), bit by bit. Decoders take it:
 * `CanvasClipsTest` and `PreviewRateTest` play it on the emulator and on phones.
 */
class BlankAvcTest {
    @Test
    fun expGolombCodes() {
        assertEquals("1", bits { ue(0) })
        assertEquals("010", bits { ue(1) })
        assertEquals("011", bits { ue(2) })
        assertEquals("00101", bits { ue(4) })
        assertEquals("0001000", bits { ue(7) })
        assertEquals("0000001000001", bits { ue(64) })
        assertEquals("1", bits { se(0) })
        assertEquals("010", bits { se(1) })
        assertEquals("011", bits { se(-1) })
    }

    @Test
    fun theParameterSets() {
        // Constrained baseline, level 3.0.
        assertArrayEquals(bytes(0x67, 0x42, 0xC0, 0x1E), BlankAvc.sps.copyOf(4))
        // The PPS a baseline CAVLC encoder writes with the deblocking control on.
        assertArrayEquals(bytes(0x68, 0xCE, 0x3C, 0x80), BlankAvc.pps)
    }

    @Test
    fun aSkippedPictureIsSixBytes() {
        // P slice, frame_num 1, QP delta 0, no deblocking, then a skip run of all 64
        // macroblocks and the stop bit: 1 00110 1 00000001 0 0 0 1 010 0000001000001 1.
        assertArrayEquals(bytes(0x41, 0x9A, 0x02, 0x28, 0x08, 0x30), BlankAvc.skipped(1))
    }

    @Test
    fun anIdrPictureCodesEveryMacroblock() {
        val idr = BlankAvc.idr(0)
        // I slice, frame_num 0, idr_pic_id 0, QP delta 0, no deblocking: 88 80 4A.
        assertArrayEquals(bytes(0x65, 0x88, 0x80, 0x4A), idr.copyOf(4))
        // Then 8 bits per macroblock: I_16x16 DC, chroma DC, QP delta 0 and an empty luma DC
        // block; then the stop bit.
        assertEquals(4 + 64 + 1, idr.size)
        assertTrue(idr.copyOfRange(4, 4 + 64).all { it == 0x27.toByte() })
        assertEquals(0x80.toByte(), idr.last())
    }

    @Test
    fun noStartCodeInsideAUnit() {
        val units = listOf(BlankAvc.sps, BlankAvc.pps, BlankAvc.idr(0), BlankAvc.idr(1)) +
            (1 until 60).map { BlankAvc.skipped(it) }
        for (unit in units) {
            for (i in 0 until unit.size - 2) {
                val zeroZero = unit[i].toInt() == 0 && unit[i + 1].toInt() == 0
                assertTrue("00 00 0${unit[i + 2]} in a unit", !zeroZero || unit[i + 2] > 3)
            }
        }
    }

    private fun bits(write: BlankAvc.BitWriter.() -> Unit): String {
        val writer = BlankAvc.BitWriter().apply(write)
        val bytes = writer.trailingBits()
        val all = bytes.joinToString("") {
            (it.toInt() and 0xFF).toString(2).padStart(8, '0')
        }
        // Drop the stop bit and the zeros after it.
        return all.substring(0, all.lastIndexOf('1'))
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
