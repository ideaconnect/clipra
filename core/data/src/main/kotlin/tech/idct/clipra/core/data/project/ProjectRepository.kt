package tech.idct.clipra.core.data.project

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import tech.idct.clipra.core.data.db.ProjectIndexDao
import tech.idct.clipra.core.data.db.ProjectIndexEntry
import tech.idct.clipra.core.data.di.IoDispatcher
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.Resolution
import java.io.File
import java.net.URI
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/** What Home shows for a project, from the index alone. */
data class ProjectSummary(
    val id: String,
    val name: String,
    val ratio: AspectRatio,
    val layout: Layout,
    val resolution: Resolution,
    val durationUs: Long,
    val updatedAtMillis: Long,
    /** One media id per cell of [layout], row by row, null where there is nothing to show. */
    val pictures: List<String?> = emptyList(),
)

/** The time source for "last edited"; injectable for tests. */
fun interface WallClock {
    fun nowMillis(): Long
}

/**
 * Projects: documents in [ProjectFiles], indexed in Room for Home. Every save updates the index,
 * so the recent list always matches the documents.
 */
@Singleton
class ProjectRepository @Inject constructor(
    private val files: ProjectFiles,
    private val index: ProjectIndexDao,
    private val clock: WallClock,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) {
    /** Projects, last edited first. */
    fun observeRecent(): Flow<List<ProjectSummary>> =
        index.observeAll().map { entries -> entries.map { it.toSummary() } }

    suspend fun create(name: String, format: Format): Project {
        val project = Project(
            id = UUID.randomUUID().toString(),
            name = name,
            format = format,
            createdAtMillis = clock.nowMillis(),
        )
        save(project)
        return project
    }

    suspend fun load(id: String): Project? = withContext(io) { files.read(id) }

    suspend fun save(project: Project): Unit = withContext(io) {
        files.write(project)
        index.upsert(project.toIndexEntry(clock.nowMillis()))
    }

    suspend fun rename(id: String, name: String) {
        val project = load(id) ?: return
        save(project.copy(name = name))
    }

    /** A copy with a new id and [name]; the original is unchanged. */
    suspend fun duplicate(id: String, name: String): Project? {
        val original = load(id) ?: return null
        val copy = original.copy(
            id = UUID.randomUUID().toString(),
            name = name,
            createdAtMillis = clock.nowMillis(),
        )
        save(copy)
        return copy
    }

    /**
     * Deletes the project, and the voiceovers recorded for it (#41) that no other project (a
     * duplicate) uses. When another project can't be read, its recordings may be in it: they stay.
     */
    suspend fun delete(id: String): Unit = withContext(io) {
        val recorded = runCatching { files.read(id) }.getOrNull()?.media.orEmpty()
            .filter { it.recorded }
            .map { it.uri }
            .toSet()
        files.delete(id)
        index.delete(id)
        if (recorded.isEmpty()) return@withContext
        val used = runCatching {
            files.ids().flatMap { other -> files.read(other)?.media.orEmpty() }.map { it.uri }
        }.getOrNull() ?: return@withContext
        for (uri in recorded - used.toSet()) {
            runCatching { File(URI(uri)).delete() }
        }
    }

    /** Opens a project for editing, saving every change through an autosaver in [scope]. */
    suspend fun open(id: String, scope: CoroutineScope): ProjectSession? {
        val project = load(id) ?: return null
        return ProjectSession(project, scope, save = ::save)
    }

    private fun Project.toIndexEntry(nowMillis: Long): ProjectIndexEntry {
        val pictures = cellPictures()
        return ProjectIndexEntry(
            id = id,
            name = name,
            updatedAtMillis = nowMillis,
            ratio = format.ratio.name,
            layout = format.layout.name,
            resolution = format.resolution.name,
            durationUs = durationUs,
            thumbnailMediaId = pictures.firstNotNullOfOrNull { it },
            cellMediaIds = pictures.joinToString(",") { it.orEmpty() },
        )
    }

    private fun ProjectIndexEntry.toSummary() = ProjectSummary(
        id = id,
        name = name,
        ratio = enumOrDefault(ratio, AspectRatio.Portrait9x16),
        layout = enumOrDefault(layout, Layout.Single),
        resolution = enumOrDefault(resolution, Resolution.FHD),
        durationUs = durationUs,
        updatedAtMillis = updatedAtMillis,
        pictures = cellMediaIds?.split(',')?.map { it.ifEmpty { null } }
            ?: listOf(thumbnailMediaId),
    )

    private inline fun <reified E : Enum<E>> enumOrDefault(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default
}
