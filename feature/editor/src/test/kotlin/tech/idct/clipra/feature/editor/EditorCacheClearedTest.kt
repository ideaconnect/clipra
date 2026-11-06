package tech.idct.clipra.feature.editor

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import tech.idct.clipra.core.data.db.ProjectIndexDao
import tech.idct.clipra.core.data.db.ProjectIndexEntry
import tech.idct.clipra.core.data.project.OpenProjects
import tech.idct.clipra.core.data.project.ProjectFiles
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.engine.media.Cutouts
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaImporter
import tech.idct.clipra.core.engine.media.Proxies
import tech.idct.clipra.core.engine.media.ReversedSounds
import tech.idct.clipra.core.engine.media.SoundAnalyzer
import tech.idct.clipra.core.engine.media.Stabilizer
import tech.idct.clipra.core.engine.media.VoiceCleaner
import tech.idct.clipra.core.engine.media.VoiceRecorder
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.models.ModelStore
import java.io.File

/**
 * A cleared cache (#217): Android empties it when storage runs low, and so does Clear cache in
 * the app's settings. When the project opens, the editor makes the proxies and thumbnails of its
 * media again, a proxy only once however often the project opens, and its header says while
 * proxies are being made.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditorCacheClearedTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The editor's work that outlives it, apart from the autosaver, so a test can wait for it. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var work: TestProxyWork
    private lateinit var cache: MediaCache
    private lateinit var originals: File
    private lateinit var projects: OpenProjects

    @Before
    fun setUp() {
        work = TestProxyWork(context)
        cache = MediaCache(folder.newFolder("cache"))
        originals = folder.newFolder("originals")
        // The issue's 9:16 project: 4K videos and a photo, with the cache emptied.
        val project = Project(
            id = "lisbon",
            name = "Lisbon weekend",
            media = listOf(video("tram"), video("alfama"), photo("stamp")),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("tram", ClipType.Video, 0),
                        clip("alfama", ClipType.Video, 3_600_000),
                    ),
                ),
                Layer("L2", clips = listOf(clip("stamp", ClipType.Photo, 0))),
            ),
        )
        val files = ProjectFiles(folder.newFolder("projects")).apply { write(project) }
        val repository = ProjectRepository(files, NoIndex, clock = { 0L }, io = Dispatchers.IO)
        projects = OpenProjects(repository, scope)
    }

    @After
    fun tearDown() {
        work.close(background)
        scope.cancel()
        background.cancel()
    }

    /** A 4K video, with stand-in bytes for its original. */
    private fun video(id: String) = MediaAsset(
        id = id,
        uri = Uri.fromFile(original(id)).toString(),
        kind = MediaKind.Video,
        displayName = "$id.mp4",
        durationUs = 3_600_000,
        width = 3840,
        height = 2160,
        hasAudio = true,
    ).also { original(id).writeBytes(ByteArray(64)) }

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

    private fun clip(media: String, type: ClipType, startUs: Long) =
        Clip("$media-clip", type, startUs, 3_600_000, MediaRef(media, 0, 3_600_000))

    @Test
    fun openingTheProjectQueuesTheProxiesTheCacheLost() {
        original("alfama").delete()

        val state = editor().awaitState { it.makingProxies }

        // The video that can be read gets its proxy again; the one whose original is gone has
        // nothing to make it from, and a photo needs none.
        assertEquals(1, work.pending("tram"))
        assertEquals(0, work.works("alfama").size)
        assertEquals(0, work.works("stamp").size)
        assertEquals(setOf("alfama"), state.missingMedia)
    }

    @Test
    fun openingTheProjectAgainQueuesNoProxyTwice() {
        val first = editor()
        first.awaitState { it.makingProxies }
        // Back from the background, as each resume does, then opened once more.
        first.recheckMedia()
        settle()
        editor().awaitState { it.makingProxies }
        settle()

        assertEquals(1, work.works("tram").size)
        assertEquals(1, work.works("alfama").size)
    }

    @Test
    fun theHeaderSaysProxiesOnceTheyAreMade() {
        val editor = editor()
        editor.awaitState { it.makingProxies }

        work.finish()

        editor.awaitState { !it.makingProxies }
        assertEquals(0, work.pending("tram"))
        assertEquals(0, work.pending("alfama"))
    }

    @Test
    fun openingTheProjectMakesTheThumbnailsTheCacheLost() {
        editor().awaitState { it.loaded }
        settle()

        assertTrue(cache.thumbnail("stamp").exists())
    }

    private fun editor(): EditorViewModel {
        val proxies = Proxies(context, cache)
        val voices = VoiceCleaner(context, cache, ModelStore(folder.newFolder()))
        return EditorViewModel(
            context = context,
            projects = projects,
            importer = MediaImporter(context, cache, proxies),
            mediaCache = cache,
            proxies = proxies,
            voices = voices,
            analyzer = SoundAnalyzer(cache, voices),
            recorder = VoiceRecorder(context),
            cutouts = Cutouts(context, cache),
            stabilizer = Stabilizer(context, cache),
            reversedSounds = ReversedSounds(context, cache),
            appScope = background,
            savedState = SavedStateHandle(mapOf("projectId" to "lisbon")),
        )
    }

    /** Runs the main looper until [done], while the editor's work off the main thread ends. */
    private fun EditorViewModel.awaitState(done: (EditorUiState) -> Boolean): EditorUiState {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (state.value.loaded && done(state.value)) return state.value
            Thread.sleep(10)
        }
        error("The editor never got there: ${state.value}")
    }

    /** Lets the editor look at its media, then waits for what it started in the background. */
    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        background.awaitIdle()
    }

    /** Home's index, which the editor doesn't read. */
    private object NoIndex : ProjectIndexDao {
        override fun observeAll(): Flow<List<ProjectIndexEntry>> = flowOf(emptyList())

        override suspend fun get(id: String): ProjectIndexEntry? = null

        override suspend fun upsert(entry: ProjectIndexEntry) = Unit

        override suspend fun delete(id: String) = Unit
    }
}
