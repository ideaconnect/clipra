package tech.idct.clipra.feature.home

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.data.db.ProjectIndexDao
import tech.idct.clipra.core.data.db.ProjectIndexEntry
import tech.idct.clipra.core.data.project.ProjectFiles
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.Thumbnails
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.Project
import java.io.File

/**
 * Home's project pictures after a cache clear (#217): a thumbnail the cache lost is made again
 * from the project's media while its original can be read, and comes from the cache after that.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CachedProjectPicturesTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private lateinit var cache: MediaCache
    private lateinit var originals: File
    private lateinit var files: ProjectFiles
    private lateinit var repository: ProjectRepository
    private lateinit var project: Project

    @Before
    fun setUp() {
        cache = MediaCache(folder.newFolder("cache"))
        originals = folder.newFolder("originals")
        files = ProjectFiles(folder.newFolder("projects"))
        repository = ProjectRepository(files, NoIndex, clock = { 0L }, io = Dispatchers.IO)
        // Two photos on the card, with the cache emptied.
        val media = listOf(photo("stamp"), photo("tram"))
        project = Project(id = "lisbon", name = "Lisbon weekend", media = media)
        files.write(project)
    }

    /** A photo whose original is a real picture, so its thumbnail can be made. */
    private fun photo(id: String): MediaAsset {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.rgb(200, 120, 60))
        original(id).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return MediaAsset(
            id = id,
            uri = Uri.fromFile(original(id)).toString(),
            kind = MediaKind.Photo,
            displayName = "$id.png",
            width = 64,
            height = 48,
        )
    }

    private fun original(id: String) = File(originals, "$id.media")

    private fun pictures() = CachedProjectPictures(context, cache, repository)

    @Test
    fun aThumbnailTheCacheLostIsMadeAgain() = runBlocking {
        assertFalse(cache.thumbnail("stamp").exists())

        val picture = pictures().picture("lisbon", "stamp")

        assertNotNull(picture)
        assertEquals(64, picture!!.width)
        assertTrue(cache.thumbnail("stamp").exists())
    }

    @Test
    fun aThumbnailInTheCacheNeedsNoProject() = runBlocking {
        pictures().picture("lisbon", "stamp")
        // The next Home reads it from the cache, the project file unread.
        files.delete("lisbon")

        assertNotNull(pictures().picture("lisbon", "stamp"))
    }

    @Test
    fun aPhotoWhoseOriginalIsGoneShowsNothingUntilItIsFoundAgain() = runBlocking {
        val tram = project.asset("tram")!!
        original("tram").delete()
        val pictures = pictures()

        assertNull(pictures.picture("lisbon", "tram"))
        assertFalse(cache.thumbnail("tram").exists())

        // The editor makes it once the file is found again (#216); Home shows it.
        photo("tram")
        Thumbnails.thumbnail(context, tram, cache)
        assertNotNull(pictures.picture("lisbon", "tram"))
    }

    /** Home's index, which the pictures don't read. */
    private object NoIndex : ProjectIndexDao {
        override fun observeAll(): Flow<List<ProjectIndexEntry>> = flowOf(emptyList())

        override suspend fun get(id: String): ProjectIndexEntry? = null

        override suspend fun upsert(entry: ProjectIndexEntry) = Unit

        override suspend fun delete(id: String) = Unit
    }
}
