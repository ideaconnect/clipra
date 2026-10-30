package tech.idct.clipra.core.data.project

import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.ProjectJson
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Project documents on disk: `<directory>/<id>/project.json`. Writes go to a temporary file
 * that is synced and then moved over the document, so a crash or a killed app leaves either the
 * old or the new document, never half of one.
 */
class ProjectFiles(private val directory: File, private val json: ProjectJson = ProjectJson()) {
    fun write(project: Project) {
        val folder = File(directory, project.id).apply { mkdirs() }
        val target = File(folder, DOCUMENT)
        val temporary = File(folder, "$DOCUMENT.tmp")
        FileOutputStream(temporary).use { out ->
            out.write(json.encode(project).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        Files.move(
            temporary.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING,
        )
    }

    /** The document, or null when there is none. Throws if it can't be read or migrated. */
    fun read(id: String): Project? {
        val file = File(File(directory, id), DOCUMENT)
        if (!file.exists()) return null
        return json.decode(file.readText(Charsets.UTF_8))
    }

    fun delete(id: String) {
        File(directory, id).deleteRecursively()
    }

    /** The ids of every project with a document. */
    fun ids(): List<String> = directory.listFiles().orEmpty()
        .filter { File(it, DOCUMENT).exists() }
        .map { it.name }

    private companion object {
        const val DOCUMENT = "project.json"
    }
}
