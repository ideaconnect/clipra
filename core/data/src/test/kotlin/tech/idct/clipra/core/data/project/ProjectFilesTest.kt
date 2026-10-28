package tech.idct.clipra.core.data.project

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import tech.idct.clipra.core.model.Project
import java.io.File

class ProjectFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writesReplaceTheDocumentAndLeaveNoTemporaryFile() {
        val directory = folder.newFolder("projects")
        val files = ProjectFiles(directory)
        files.write(Project(id = "p1", name = "First"))
        files.write(Project(id = "p1", name = "Second"))

        assertEquals("Second", files.read("p1")!!.name)
        assertEquals(listOf("project.json"), File(directory, "p1").list()!!.toList())
    }

    @Test
    fun missingAndDeletedProjectsReadAsNull() {
        val directory = folder.newFolder("projects")
        val files = ProjectFiles(directory)
        assertNull(files.read("nothing"))

        files.write(Project(id = "p1", name = "Gone"))
        files.delete("p1")

        assertNull(files.read("p1"))
        assertFalse(File(directory, "p1").exists())
    }
}
