package tech.idct.clipra.core.engine.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** The person mask file (#50): frames by media time, found or not, and broken files. */
class CutoutMasksTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val size = CutoutMasks.SIZE

    /** A mask with the top [rows] rows a person. */
    private fun mask(rows: Int) = ByteArray(size * size) { if (it / size < rows) -1 else 0 }

    private fun write(file: File, vararg frames: Pair<Long, ByteArray>): File {
        CutoutMasks.Writer(file).apply {
            frames.forEach { (time, mask) -> add(time, mask) }
            finish()
        }
        return file
    }

    @Test
    fun framesComeBackByMediaTime() {
        val file = write(
            File(folder.root, "a.masks"),
            0L to mask(0),
            33_333L to mask(64),
            66_667L to mask(128),
        )
        assertFalse(File(file.path + ".part").exists())
        CutoutMasks.open(file)!!.use { masks ->
            assertEquals(3, masks.frames)
            assertTrue(masks.found)
            val out = ByteArray(size * size)
            // The frame on screen: the last at or before the time, the first before any.
            assertEquals(1, masks.frameAt(50_000, out))
            assertArrayEquals(mask(64), out)
            assertEquals(2, masks.frameAt(10_000_000, out))
            assertArrayEquals(mask(128), out)
            assertEquals(0, masks.indexAt(-5))
            masks.frame(0, out)
            assertArrayEquals(mask(0), out)
        }
    }

    @Test
    fun aMaskWithoutAPersonIsNotFound() {
        val file = write(File(folder.root, "empty.masks"), 0L to mask(0), 1_000L to mask(2))
        CutoutMasks.open(file)!!.use { assertFalse(it.found) }
    }

    @Test
    fun brokenOrUnfinishedFilesAreNotRead() {
        assertNull(CutoutMasks.open(File(folder.root, "missing.masks")))
        val file = write(File(folder.root, "b.masks"), 0L to mask(10))
        file.writeBytes(file.readBytes().copyOf(file.length().toInt() - 3))
        assertNull(CutoutMasks.open(file))
        // Stopped part way: nothing in place.
        val stopped = File(folder.root, "c.masks")
        CutoutMasks.Writer(stopped).use { it.add(0, mask(10)) }
        assertFalse(stopped.exists())
        assertFalse(File(stopped.path + ".part").exists())
    }

    @Test
    fun cachedMasksAreSharedUntilTheFileGoes() {
        val file = write(File(folder.root, "d.masks"), 0L to mask(100))
        val first = CutoutMasks.cached(file)!!
        assertSame(first, CutoutMasks.cached(file))
        // Windows won't delete a file that is open; Android and Linux do.
        assumeTrue(file.delete())
        assertNull(CutoutMasks.cached(file))
    }
}
