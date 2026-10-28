package tech.idct.clipra.core.data.db

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
 * (docs/architecture.md, section 3); the project model issue (#10) settles the final tables.
 */
@Database(entities = [ProjectIndexEntry::class], version = 1)
abstract class ClipraDatabase : RoomDatabase() {
    abstract fun projectIndexDao(): ProjectIndexDao
}

/** One row per project on Home. */
@Entity(tableName = "project_index")
data class ProjectIndexEntry(
    @PrimaryKey val id: String,
    val name: String,
    val updatedAtMillis: Long,
)

@Dao
interface ProjectIndexDao {
    @Query("SELECT * FROM project_index ORDER BY updatedAtMillis DESC")
    fun observeAll(): Flow<List<ProjectIndexEntry>>

    @Upsert
    suspend fun upsert(entry: ProjectIndexEntry)
}
