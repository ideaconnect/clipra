package tech.idct.clipra.feature.effects.cutout

import android.content.Context
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.Configuration
import androidx.work.CoroutineWorker
import androidx.work.ListenableWorker
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
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
import tech.idct.clipra.core.engine.media.CutoutWorker
import tech.idct.clipra.core.engine.media.Cutouts
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.ChromaKey
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Cutout
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project

/**
 * The search for people on the Cutout board (#240): opening the board starts it, switching to
 * Chroma key stops it unless another clip of the same media cuts its people out, and switching
 * back to Remove background starts it again.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CutoutViewModelTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val boards = mutableListOf<CutoutViewModel>()
    private lateinit var cache: MediaCache

    /** The issue's 12 s green-screen take. */
    private val studio = MediaAsset(
        id = "studio",
        uri = "content://media/studio",
        kind = MediaKind.Video,
        displayName = "studio.mp4",
        durationUs = 12_000_000,
        width = 1920,
        height = 1080,
    )

    @Before
    fun setUp() {
        // Robolectric has no segmenter: the search runs until it is cancelled, as a long clip's
        // does on a phone.
        val factory = object : WorkerFactory() {
            override fun createWorker(
                appContext: Context,
                workerClassName: String,
                workerParameters: WorkerParameters,
            ): ListenableWorker = object : CoroutineWorker(appContext, workerParameters) {
                override suspend fun doWork(): Result = awaitCancellation()
            }
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(
            context,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(factory)
                .build(),
        )
        cache = MediaCache(folder.newFolder("cache"))
    }

    @After
    fun tearDown() {
        boards.forEach { it.viewModelScope.cancel() }
        WorkManager.getInstance(context).cancelAllWork().result.get()
        scope.cancel()
    }

    private fun take(id: String, startUs: Long = 0, cutout: Cutout? = null) = Clip(
        id = id,
        type = ClipType.Video,
        startUs = startUs,
        durationUs = 12_000_000,
        source = MediaRef(studio.id, 0, 12_000_000),
        cutout = cutout,
    )

    @Test
    fun chromaKeyStopsTheSearchAndRemoveBackgroundStartsItAgain() {
        val board = open(take("take"))
        board.awaitState { it.clip?.cutout == Cutout() }
        assertEquals(1, searching())

        board.setChroma(true)
        board.awaitState { it.cutout.key != null }
        assertEquals(0, searching())
        assertTrue(searches().any { it.state == WorkInfo.State.CANCELLED })

        board.setChroma(false)
        board.awaitState { it.cutout.key == null }
        assertEquals(1, searching())
    }

    @Test
    fun theSearchGoesOnWhileAnotherClipOfTheMediaCutsOutItsPeople() {
        val board = open(take("take"), take("take-2", startUs = 12_000_000, cutout = Cutout()))
        board.awaitState { it.clip?.cutout == Cutout() }

        board.setChroma(true)
        board.awaitState { it.cutout.key != null }

        assertEquals(1, searching())
    }

    @Test
    fun anotherClipOfTheMediaKeyedNeedsNoSearch() {
        val keyed = Cutout(key = ChromaKey())
        val board = open(take("take"), take("take-2", startUs = 12_000_000, cutout = keyed))
        board.awaitState { it.clip?.cutout == Cutout() }

        board.setChroma(true)
        board.awaitState { it.cutout.key != null }

        assertEquals(0, searching())
    }

    /** The Cutout board on the first of [clips], in a project with the green-screen take. */
    private fun open(vararg clips: Clip): CutoutViewModel {
        val project = Project(
            id = "green",
            name = "Green screen",
            media = listOf(studio),
            layers = listOf(Layer("L1", clips = clips.toList())),
        )
        val files = ProjectFiles(folder.newFolder("projects")).apply { write(project) }
        val repository = ProjectRepository(files, NoIndex, clock = { 0L }, io = Dispatchers.IO)
        return CutoutViewModel(
            context = context,
            projects = OpenProjects(repository, scope),
            cutouts = Cutouts(context, cache),
            mediaCache = cache,
            appScope = scope,
            savedState = SavedStateHandle(mapOf("projectId" to "green", "clipId" to clips[0].id)),
        ).also { boards += it }
    }

    /** Every search for the take's people queued so far, finished or not. */
    private fun searches(): List<WorkInfo> =
        WorkManager.getInstance(context).getWorkInfosForUniqueWork(CutoutWorker.name(studio.id))
            .get()

    /** How many searches for the take's people are queued or running. */
    private fun searching(): Int = searches().count { !it.state.isFinished }

    /** Runs the main looper until [done], while the board's work off the main thread ends. */
    private fun CutoutViewModel.awaitState(done: (CutoutUiState) -> Boolean): CutoutUiState {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (state.value.loaded && done(state.value)) return state.value
            Thread.sleep(10)
        }
        error("The board never got there: ${state.value}")
    }

    /** Home's index, which the board doesn't read. */
    private object NoIndex : ProjectIndexDao {
        override fun observeAll(): Flow<List<ProjectIndexEntry>> = flowOf(emptyList())

        override suspend fun get(id: String): ProjectIndexEntry? = null

        override suspend fun upsert(entry: ProjectIndexEntry) = Unit

        override suspend fun delete(id: String) = Unit
    }
}
