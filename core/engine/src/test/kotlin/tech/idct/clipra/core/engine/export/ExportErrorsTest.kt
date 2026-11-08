package tech.idct.clipra.core.engine.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.IOException

/** The words a failed export leaves in the log (#236); the Media3 side is ExportFailureLogTest. */
class ExportErrorsTest {
    @Test
    fun theLogLineNamesTheFailureAndMedia3sCode() {
        assertEquals(
            "Export failed: Other (ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED)",
            ExportErrors.message(ExportFailure.Other, "ERROR_CODE_VIDEO_FRAME_PROCESSING_FAILED"),
        )
        assertEquals("Export failed: Storage", ExportErrors.message(ExportFailure.Storage, null))
    }

    @Test
    fun aFailureOutsideTransformerHasNoCode() {
        val publishing = ExportFailedException(ExportFailure.Storage, IOException("No space left"))

        assertNull(ExportErrors.codeName(publishing))
        assertNull(ExportErrors.codeName(IllegalStateException("No frame")))
    }

    @Test
    fun aCauseChainThatLoopsEnds() {
        val first = IllegalStateException("first")
        val second = IllegalStateException("second", first)
        first.initCause(second)

        assertNull(ExportErrors.codeName(first))
    }
}
