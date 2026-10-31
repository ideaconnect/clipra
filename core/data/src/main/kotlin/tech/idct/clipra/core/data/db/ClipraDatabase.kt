package tech.idct.clipra.core.data.db

import androidx.room.AutoMigration
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * The app's database: indexes only. A project itself is a versioned JSON document
 * (docs/architecture.md, section 3); the index holds what Home shows without reading it.
 */
@Database(
    entities = [ProjectIndexEntry::class],
    version = 3,
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
abstract class ClipraDatabase : RoomDatabase() {
    abstract fun projectIndexDao(): ProjectIndexDao
}

/**
 * One row per project on Home: name, format badge, duration, last edited, and the pictures in
 * its frame: [cellMediaIds] holds one media id per cell, comma-separated, empty for a cell with
 * nothing; rows saved before it was added have only [thumbnailMediaId].
 */
@Entity(tableName = "project_index")
data class ProjectIndexEntry(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAtMillis: Long,
    @ColumnInfo(defaultValue = "Portrait9x16") val ratio: String = "Portrait9x16",
    @ColumnInfo(defaultValue = "Single") val layout: String = "Single",
    @ColumnInfo(defaultValue = "FHD") val resolution: String = "FHD",
    @ColumnInfo(defaultValue = "0") val durationUs: Long = 0,
    val thumbnailMediaId: String? = null,
    val cellMediaIds: String? = null,
)

@Dao
interface ProjectIndexDao {
    @Query("SELECT * FROM project_index ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<ProjectIndexEntry>>

    @Query("SELECT * FROM project_index WHERE id = :id")
    suspend fun get(id: String): ProjectIndexEntry?

    @Upsert
    suspend fun upsert(entry: ProjectIndexEntry)

    @Query("DELETE FROM project_index WHERE id = :id")
    suspend fun delete(id: String)
}
