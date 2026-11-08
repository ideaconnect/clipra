package tech.idct.clipra.feature.editor

import android.content.Context
import android.media.MediaMetadataRetriever
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowMediaMetadataRetriever
import org.robolectric.shadows.util.DataSource
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
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.models.ModelStore
import java.io.File

/**
 * Home's "Auto captions" (#231) starts a new project on the photo picker: once the media picked
 * there brings sound, the editor goes on to Auto captions; without sound it asks for a clip with
 * sound and stays. Media added any other way never opens Auto captions.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditorAutoCaptionsTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The editor's work that outlives it, apart from the autosaver, so a test can wait for it. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cache: MediaCache
    private lateinit var work: TestProxyWork

    /** A project as New project makes it: one empty layer. */
    private val project = Project(id = "captions", name = "Lisbon weekend")

    @Before
    fun setUp() {
        work = TestProxyWork(context)
        cache = MediaCache(folder.newFolder("cache"))
    }

    @After
    fun tearDown() {
        work.close(background)
        scope.cancel()
        background.cancel()
    }

    @Test
    fun aVideoWithSoundGoesOnToAutoCaptions() {
        val editor = editor()
        editor.awaitState { true }

        editor.import(listOf(video("tram", sound = true)), thenCaptions = true)

        val state = editor.awaitState { !it.importing && it.captionsNext }
        assertNull(state.message)
        // Opened once: back from Auto captions, the editor stays.
        editor.captionsOpened()
        assertFalse(editor.state.value.captionsNext)
        val clip = editor.awaitState { it.project!!.hasSound }.project!!.layers[0].clips.single()
        assertEquals(ClipType.Video, clip.type)
    }

    @Test
    fun withoutSoundTheEditorAsksForAClipWithSound() {
        val editor = editor()
        editor.awaitState { true }

        editor.import(listOf(video("screen", sound = false)), thenCaptions = true)

        val state = editor.awaitState { s ->
            !s.importing && s.project!!.layers.sumOf { it.clips.size } == 1
        }
        assertFalse(state.captionsNext)
        assertEquals(EditorMessage.NeedsSound, state.message)
    }

    @Test
    fun mediaAddedOtherwiseNeverOpensAutoCaptions() {
        val editor = editor()
        editor.awaitState { true }

        editor.import(listOf(video("tram", sound = true)))

        val state = editor.awaitState { s ->
            !s.importing && s.project!!.layers.sumOf { it.clips.size } == 1
        }
        assertTrue(state.project!!.hasSound)
        assertFalse(state.captionsNext)
        assertNull(state.message)
    }

    @Test
    fun aPickerClosedWithNothingPickedLeavesTheEmptyProject() {
        val editor = editor()
        editor.awaitState { true }

        editor.import(emptyList(), thenCaptions = true)

        val state = editor.awaitState { true }
        assertFalse(state.importing)
        assertFalse(state.captionsNext)
        assertNull(state.message)
        assertTrue(state.project!!.layers.all { it.clips.isEmpty() })
    }

    /** A 4 s video named [name], with a sound track when [sound], as a picked file. */
    private fun video(name: String, sound: Boolean): Uri {
        val file = File(folder.newFolder(name), "$name.mp4")
            .apply { writeBytes(ByteArray(4_096) { (it * name.length).toByte() }) }
        val uri = Uri.fromFile(file)
        val source = DataSource.toDataSource(context, uri)
        ShadowMediaMetadataRetriever.addMetadata(
            source,
            MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO,
            "yes",
        )
        if (sound) {
            ShadowMediaMetadataRetriever.addMetadata(
                source,
                MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO,
                "yes",
            )
        }
        ShadowMediaMetadataRetriever.addMetadata(
            source,
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "4000",
        )
        return uri
    }

    private fun editor(): EditorViewModel {
        val files = ProjectFiles(folder.newFolder("projects"))
        files.write(project)
        val repository = ProjectRepository(files, NoIndex, clock = { 0L }, io = Dispatchers.IO)
        val voices = VoiceCleaner(context, cache, ModelStore(folder.newFolder("models")))
        val proxies = Proxies(context, cache)
        return EditorViewModel(
            context = context,
            projects = OpenProjects(repository, scope),
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
            savedState = SavedStateHandle(mapOf("projectId" to project.id)),
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

    /** Home's index, which the editor doesn't read. */
    private object NoIndex : ProjectIndexDao {
        override fun observeAll(): Flow<List<ProjectIndexEntry>> = flowOf(emptyList())

        override suspend fun get(id: String): ProjectIndexEntry? = null

        override suspend fun upsert(entry: ProjectIndexEntry) = Unit

        override suspend fun delete(id: String) = Unit
    }
}
