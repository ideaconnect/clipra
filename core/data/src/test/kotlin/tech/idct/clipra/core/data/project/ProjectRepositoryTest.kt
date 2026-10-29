package tech.idct.clipra.core.data.project

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import tech.idct.clipra.core.data.db.ClipraDatabase
import tech.idct.clipra.core.model.AspectRatio
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Format
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.Layout
import tech.idct.clipra.core.model.Resolution
import tech.idct.clipra.core.model.edit.AddClip
import tech.idct.clipra.core.model.edit.EditRejected
import tech.idct.clipra.core.model.edit.LayerTarget
import tech.idct.clipra.core.model.edit.SetLayerLocked
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ProjectRepositoryTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var database: ClipraDatabase
    private lateinit var repository: ProjectRepository
    private lateinit var directory: File
    private var now = 1_000L

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, ClipraDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        directory = folder.newFolder("projects")
        repository = ProjectRepository(
            files = ProjectFiles(directory),
            index = database.projectIndexDao(),
            clock = { now },
            io = UnconfinedTestDispatcher(),
        )
    }

    @After
    fun tearDown() = database.close()

    @Test
    fun createdProjectsAppearOnHomeWithTheirFormat() = runTest {
        val format =
            Format(
                ratio = AspectRatio.Landscape16x9,
                layout = Layout.Grid2x2,
                resolution = Resolution.UHD,
            )
        val project = repository.create("Unboxing split-screen", format)

        val recent = repository.observeRecent().first()

        assertEquals(1, recent.size)
        with(recent.single()) {
            assertEquals(project.id, id)
            assertEquals("Unboxing split-screen", name)
            assertEquals(AspectRatio.Landscape16x9, ratio)
            assertEquals(Layout.Grid2x2, layout)
            assertEquals(Resolution.UHD, resolution)
            assertEquals(1_000L, updatedAtMillis)
        }
        assertEquals(project, repository.load(project.id))
    }

    @Test
    fun savingUpdatesDurationAndOrdersByLastEdited() = runTest {
        val first = repository.create("First", Format())
        now = 2_000
        val second = repository.create("Second", Format())
        now = 3_000
        val clip = Clip("c", ClipType.Photo, startUs = 0, durationUs = 42_000_000)
        repository.save(first.copy(layers = listOf(Layer("L1", clips = listOf(clip)))))

        val recent = repository.observeRecent().first()

        assertEquals(listOf(first.id, second.id), recent.map { it.id })
        assertEquals(42_000_000L, recent.first().durationUs)
    }

    @Test
    fun renameDuplicateAndDelete() = runTest {
        val project = repository.create("Morning run", Format())

        repository.rename(project.id, "Evening run")
        assertEquals("Evening run", repository.load(project.id)!!.name)

        val copy = repository.duplicate(project.id, "Evening run copy")!!
        assertNotEquals(project.id, copy.id)
        assertEquals(2, repository.observeRecent().first().size)

        repository.delete(project.id)
        assertNull(repository.load(project.id))
        assertFalse(File(directory, project.id).exists())
        assertEquals(listOf(copy.id), repository.observeRecent().first().map { it.id })
    }

    @Test
    fun aSessionAutosavesEditsUndoAndRedo() = runTest {
        val project = repository.create("Lisbon weekend", Format())
        val session = repository.open(project.id, backgroundScope)!!
        assertFalse(session.state.value.canUndo)

        assertNull(session.edit { it.copy(name = "Lisbon, day one") })
        assertTrue(session.state.value.canUndo)
        assertFalse(session.state.value.canRedo)
        session.flush()
        assertEquals("Lisbon, day one", repository.load(project.id)!!.name)
        assertTrue(repository.observeRecent().first().any { it.name == "Lisbon, day one" })

        session.undo()
        assertTrue(session.state.value.canRedo)
        session.flush()
        assertEquals("Lisbon weekend", repository.load(project.id)!!.name)
    }

    @Test
    fun aRefusedEditIsReported() = runTest {
        val project = repository.create("Locked", Format())
        val session = repository.open(project.id, backgroundScope)!!
        session.edit(SetLayerLocked("L1", true))
        val refused = session.edit(
            AddClip(Clip("c", ClipType.Photo, 0, 1_000_000), LayerTarget.Existing("L1")),
        )
        assertEquals(EditRejected.Reason.LayerLocked, refused?.reason)
    }

    @Test
    fun openProjectsShareOneSessionUntilTheLastRelease() = runTest {
        val project = repository.create("Shared", Format())
        val open = OpenProjects(repository, backgroundScope)
        val editor = open.acquire(project.id)!!
        val filters = open.acquire(project.id)!!
        assertSame(editor, filters)
        filters.edit { it.copy(name = "Renamed") }
        assertTrue(editor.state.value.canUndo)

        open.release(project.id)
        assertSame(editor, open.acquire(project.id))
        open.release(project.id)
        open.release(project.id)
        // Closed and saved; opening again starts a new session.
        assertEquals("Renamed", repository.load(project.id)!!.name)
        assertNotSame(editor, open.acquire(project.id))
        assertNull(open.acquire("missing"))
    }
}
