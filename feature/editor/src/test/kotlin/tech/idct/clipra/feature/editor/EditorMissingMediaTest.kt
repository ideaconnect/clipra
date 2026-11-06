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
import tech.idct.clipra.core.engine.media.MediaInspector
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
import tech.idct.clipra.core.model.Speed
import tech.idct.clipra.core.model.SpeedCurve
import tech.idct.clipra.core.models.ModelStore
import java.io.File

/**
 * Media whose original is gone (#213): the editor flags it whatever the cache holds, since the
 * export reads only originals, and tells which of it the preview still plays from a proxy. Its
 * file can be found again (#216), which brings its clips back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditorMissingMediaTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cache: MediaCache
    private lateinit var originals: File
    private lateinit var project: Project

    @Before
    fun setUp() {
        cache = MediaCache(folder.newFolder("cache"))
        originals = folder.newFolder("originals")
        // Two 4K videos on L1 and a photo on L2, with their originals in place.
        project = Project(
            id = "lisbon",
            name = "Lisbon weekend",
            media = listOf(
                asset("gone", MediaKind.Video),
                asset("stamp", MediaKind.Photo),
                asset("tram", MediaKind.Video),
            ),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("gone", ClipType.Video, 0),
                        clip("tram", ClipType.Video, 3_600_000),
                    ),
                ),
                Layer("L2", clips = listOf(clip("stamp", ClipType.Photo, 0))),
            ),
        )
    }

    @After
    fun tearDown() = scope.cancel()

    /** A 4K video, as the issue's, or a photo, with its original in [originals]. */
    private fun asset(id: String, kind: MediaKind) = MediaAsset(
        id = id,
        uri = Uri.fromFile(original(id)).toString(),
        kind = kind,
        displayName = "$id.mp4",
        durationUs = 3_600_000,
        width = 3840,
        height = 2160,
        hasAudio = kind == MediaKind.Video,
    ).also { original(it.id).writeBytes(ByteArray(64)) }

    private fun original(id: String) = File(originals, "$id.media")

    private fun cacheProxy(id: String) =
        cache.proxy(id).apply { parentFile?.mkdirs() }.writeBytes(ByteArray(64))

    private fun clip(media: String, type: ClipType, startUs: Long) =
        Clip("$media-clip", type, startUs, 3_600_000, MediaRef(media, 0, 3_600_000))

    @Test
    fun anOriginalThatIsGoneIsMissingThoughItsProxyIsCached() {
        // The proxies of both videos are made; then the video and the photo are deleted.
        cacheProxy("gone")
        cacheProxy("tram")
        original("gone").delete()
        original("stamp").delete()

        val state = editor().awaitState { "gone" in it.missingMedia }

        assertEquals(setOf("gone", "stamp"), state.missingMedia)
        // The preview still plays the video from its proxy; the photo has none.
        assertEquals(setOf("gone"), state.previewOnlyMedia)
    }

    @Test
    fun theEditorLooksAgainWhenAsked() {
        cacheProxy("gone")
        cacheProxy("tram")
        original("gone").delete()
        val editor = editor()
        editor.awaitState { "gone" in it.previewOnlyMedia }

        // In the background, the system clears the proxy and another original goes.
        cache.proxy("gone").delete()
        original("tram").delete()
        editor.recheckMedia()

        val state = editor.awaitState { "tram" in it.missingMedia }
        assertEquals(setOf("gone", "tram"), state.missingMedia)
        assertEquals(setOf("tram"), state.previewOnlyMedia)
    }

    @Test
    fun pickingAClipWhoseOriginalIsGoneSaysWhatThatMeans() {
        cacheProxy("gone")
        original("gone").delete()
        original("stamp").delete()
        val editor = editor()
        editor.awaitState { "gone" in it.previewOnlyMedia }

        editor.select("gone-clip")
        assertEquals(EditorMessage.OriginalMissing, editor.state.value.message)

        // Once said, picking it again doesn't say it again.
        editor.dismissMessage()
        editor.select("gone-clip")
        assertNull(editor.state.value.message)

        // A clip with no media to play says "Missing media" on itself; one in place, nothing.
        editor.select("stamp-clip")
        assertNull(editor.state.value.message)
        editor.select("tram-clip")
        assertNull(editor.state.value.message)
    }

    /**
     * The issue's sound (#216): habitat.mp3 in two clips, the second from 6.5 s at the Hero
     * ramp, with its original in place; returns its id, the fingerprint of its bytes.
     */
    private fun soundProject(): String {
        val file = File(originals, "habitat.mp3").apply { writeBytes(sound(1)) }
        val id = MediaInspector.fingerprint(context, Uri.fromFile(file))
        val hero = Speed(ramp = "hero")
        project = Project(
            id = "lisbon",
            name = "Lisbon weekend",
            media = listOf(
                MediaAsset(
                    id,
                    Uri.fromFile(file).toString(),
                    MediaKind.Audio,
                    "habitat.mp3",
                    durationUs = 10_000_000,
                    sizeBytes = file.length(),
                ),
            ),
            layers = listOf(
                Layer("L1"),
                Layer(
                    "L2",
                    clips = listOf(
                        Clip("a", ClipType.Audio, 0, 6_500_000, MediaRef(id, 0, 6_500_000)),
                        Clip(
                            "b",
                            ClipType.Audio,
                            6_500_000,
                            SpeedCurve.timelineDuration(hero, 3_500_000),
                            MediaRef(id, 6_500_000, 10_000_000),
                            speed = hero,
                        ),
                    ),
                ),
            ),
        )
        return id
    }

    /** Bytes standing in for a sound file; a different [seed] makes a different file. */
    private fun sound(seed: Int) = ByteArray(4_096) { (it * seed).toByte() }

    @Test
    fun theSameFileFoundAgainBringsEveryClipBack() {
        val id = soundProject()
        val clips = project.layers.flatMap { it.clips }
        File(originals, "habitat.mp3").delete()
        val editor = editor()
        editor.awaitState { id in it.missingMedia }

        // The same bytes, copied back somewhere else: a new link, the same fingerprint.
        val restored = File(folder.newFolder("restored"), "habitat (1).mp3")
            .apply { writeBytes(sound(1)) }
        editor.relink(id, Uri.fromFile(restored))

        val state = editor.awaitState { id !in it.missingMedia }
        val relinked = state.project!!
        assertEquals(Uri.fromFile(restored).toString(), relinked.asset(id)!!.uri)
        // Every clip as it was: in and out points, speed.
        assertEquals(clips, relinked.layers.flatMap { it.clips })
        assertEquals(EditorMessage.Relinked, state.message)
        assertNull(state.relinkCandidate)
    }

    @Test
    fun anotherFileIsUsedOnlyOnceTheUserSaysSo() {
        val id = soundProject()
        File(originals, "habitat.mp3").delete()
        val editor = editor()
        editor.awaitState { id in it.missingMedia }
        // An 8 s file with other bytes: the 6.5-10 s part doesn't fit in it.
        val other = File(folder.newFolder("other"), "habitat-short.mp3")
            .apply { writeBytes(sound(3)) }
        val uri = Uri.fromFile(other)
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, uri),
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "8000",
        )

        editor.relink(id, uri)
        val asked = editor.awaitState { it.relinkCandidate != null }.relinkCandidate!!
        assertEquals(id, asked.mediaId)
        assertEquals(1, asked.refitted)
        assertEquals(2, asked.clips)
        // The import keeps what describes the file if it goes missing too.
        assertEquals(other.length(), asked.asset.sizeBytes)
        assertEquals(8_000_000L, asked.asset.durationUs)
        // Nothing changes until the user answers; Cancel keeps the media missing.
        assertEquals(project.media, editor.state.value.project!!.media)
        editor.cancelRelink()
        assertNull(editor.state.value.relinkCandidate)
        assertEquals(project, editor.state.value.project)

        editor.relink(id, uri)
        editor.awaitState { it.relinkCandidate != null }
        editor.confirmRelink()

        val newId = asked.asset.id
        val state = editor.awaitState {
            it.project!!.asset(id) == null && newId !in it.missingMedia
        }
        val relinked = state.project!!
        assertEquals(listOf(newId), relinked.media.map { it.id })
        assertEquals(MediaRef(newId, 0, 6_500_000), relinked.findClip("a")!!.second.source)
        // Moved back just enough to end with the shorter file, at the same speed.
        val second = relinked.findClip("b")!!.second
        assertEquals(MediaRef(newId, 4_500_000, 8_000_000), second.source)
        assertEquals(Speed(ramp = "hero"), second.speed)
        assertTrue(state.missingMedia.isEmpty())
    }

    private fun editor(): EditorViewModel {
        val files = ProjectFiles(folder.newFolder("projects"))
        files.write(project)
        val repository = ProjectRepository(files, NoIndex, clock = { 0L }, io = Dispatchers.IO)
        val voices = VoiceCleaner(context, cache, ModelStore(folder.newFolder("models")))
        return EditorViewModel(
            context = context,
            projects = OpenProjects(repository, scope),
            importer = MediaImporter(context, cache),
            mediaCache = cache,
            voices = voices,
            analyzer = SoundAnalyzer(cache, voices),
            recorder = VoiceRecorder(context),
            cutouts = Cutouts(context, cache),
            stabilizer = Stabilizer(context, cache),
            reversedSounds = ReversedSounds(context, cache),
            appScope = scope,
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
