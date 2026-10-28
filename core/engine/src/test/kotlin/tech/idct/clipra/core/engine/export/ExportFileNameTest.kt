package tech.idct.clipra.core.engine.export

import org.junit.Assert.assertEquals
import org.junit.Test

class ExportFileNameTest {
    @Test
    fun projectNamesBecomePlainFileNames() {
        assertEquals("lisbon-weekend.mp4", ExportManager.fileName("Lisbon weekend"))
        assertEquals("wycieczka-do-lodzi.mp4", ExportManager.fileName("Wycieczka do Łodzi!"))
        assertEquals("zolta-lodz.mp4", ExportManager.fileName("Żółta łódź"))
        assertEquals("clipra.mp4", ExportManager.fileName("???"))
    }
}
