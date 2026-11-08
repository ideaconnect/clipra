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
import org.robolectric.Robolectric
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
import tech.idct.clipra.core.engine.media.DecodedSounds
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.engine.media.MediaImporter
import tech.idct.clipra.core.engine.media.MediaInspector
import tech.idct.clipra.core.engine.media.Proxies
import tech.idct.clipra.core.engine.media.ReversedSounds
import tech.idct.clipra.core.engine.media.ReversedVideos
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
import java.util.TimeZone

/**
 * Media whose original is gone (#213): the editor flags it whatever the cache holds, since the
 * export reads only originals, and tells which of it the preview still plays from a proxy. Its
 * file can be found again (#216), or picked again through Replace or Add media (#214), which
 * brings its clips back. Media the photo picker hands over under a number gets a name that
 * still says which file it was once it is gone (#215).
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class EditorMissingMediaTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** The editor's work that outlives it, apart from the autosaver, so a test can wait for it. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var cache: MediaCache
    private lateinit var originals: File
    private lateinit var project: Project
    private lateinit var work: TestProxyWork

    @Before
    fun setUp() {
        // The 4K videos with their originals in place get their proxies queued (#217).
        work = TestProxyWork(context)
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
    fun tearDown() {
        work.close(background)
        scope.cancel()
        background.cancel()
    }

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

    /** The issue's sound deleted and put back somewhere else: a new link, the same bytes. */
    private fun restoreSound(): File {
        val file = File(folder.newFolder("restored"), "habitat (1).mp3")
            .apply { writeBytes(sound(1)) }
        // The same 10 s as the lost file.
        ShadowMediaMetadataRetriever.addMetadata(
            DataSource.toDataSource(context, Uri.fromFile(file)),
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "10000",
        )
        return file
    }

    @Test
    fun replacingAMissingClipWithTheSameFileMakesItAvailableAgain() {
        val id = soundProject()
        val second = project.findClip("b")!!.second
        File(originals, "habitat.mp3").delete()
        val editor = editor()
        editor.awaitState { id in it.missingMedia }
        val restored = restoreSound()

        editor.select("a")
        editor.replace(Uri.fromFile(restored))

        // The media takes the new link (#214), so every clip of it plays again.
        val state = editor.awaitState { id !in it.missingMedia }
        val relinked = state.project!!
        assertEquals(listOf(id), relinked.media.map { it.id })
        assertEquals(Uri.fromFile(restored).toString(), relinked.asset(id)!!.uri)
        assertEquals("habitat (1).mp3", relinked.asset(id)!!.displayName)
        // What the project knows about the file stays.
        assertEquals(10_000_000L, relinked.asset(id)!!.durationUs)
        assertEquals(MediaRef(id, 0, 6_500_000), relinked.findClip("a")!!.second.source)
        // The other clip of it comes back as it was: its part, its speed.
        assertEquals(second, relinked.findClip("b")!!.second)
    }

    @Test
    fun addingTheSameFileAgainMakesNoMissingClip() {
        val id = soundProject()
        val clips = project.layers.flatMap { it.clips }
        File(originals, "habitat.mp3").delete()
        val editor = editor()
        editor.awaitState { id in it.missingMedia }
        val restored = restoreSound()

        editor.import(listOf(Uri.fromFile(restored)))

        val state = editor.awaitState { s ->
            !s.importing && s.project!!.layers.sumOf { it.clips.size } == clips.size + 1
        }
        val added = state.project!!
        // One media for the file, at the link just picked: neither the new clip nor the old
        // ones are missing.
        assertEquals(listOf(id), added.media.map { it.id })
        assertEquals(Uri.fromFile(restored).toString(), added.asset(id)!!.uri)
        val new = added.layers.flatMap { it.clips }.single { it.id !in setOf("a", "b") }
        assertEquals(id, new.source!!.mediaId)
        assertTrue(clips.all { it in added.layers.flatMap { layer -> layer.clips } })
        editor.awaitState { id !in it.missingMedia }
    }

    /** 14:30 UTC on 5 November 2026: 15:30 in Warsaw. */
    private val takenAt = 1_793_889_000_000L

    /**
     * Imports a video through [PhotoPickerProvider] as it names it ("30.mp4"), in Warsaw, and
     * returns the editor with it on the timeline, and the video's file.
     */
    private fun pickVideo(answersDateTaken: Boolean): Pair<EditorViewModel, File> {
        val picker = Robolectric.setupContentProvider(PhotoPickerProvider::class.java, "media")
        picker.answersDateTaken = answersDateTaken
        val file = File(folder.newFolder("camera"), "PXL_20261105_143000.mp4")
            .apply { writeBytes(sound(5)) }
        picker.items["30"] = PhotoPickerProvider.Item(file, "video/mp4", takenAt)
        val uri = PhotoPickerProvider.uri("30")
        val source = DataSource.toDataSource(context, uri)
        ShadowMediaMetadataRetriever.addMetadata(
            source,
            MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO,
            "yes",
        )
        ShadowMediaMetadataRetriever.addMetadata(
            source,
            MediaMetadataRetriever.METADATA_KEY_DURATION,
            "4000",
        )
        // The date the camera wrote into the file, in UTC.
        ShadowMediaMetadataRetriever.addMetadata(
            source,
            MediaMetadataRetriever.METADATA_KEY_DATE,
            "20261105T143000.000Z",
        )
        val editor = editor()
        editor.awaitState { true }
        editor.import(listOf(uri))
        editor.awaitState { state ->
            !state.importing && state.project!!.media.any { it.uri == uri.toString() }
        }
        return editor to file
    }

    private fun EditorViewModel.picked(): MediaAsset =
        state.value.project!!.media.single { it.uri == PhotoPickerProvider.uri("30").toString() }

    @Test
    @Config(qualifiers = "en-rGB")
    fun aVideoFromThePhotoPickerIsNamedByWhenItWasTakenAndKeepsItWhenGone() = inWarsaw {
        val (editor, file) = pickVideo(answersDateTaken = true)

        // Not "30.mp4", the number the picker gives it (#215).
        val video = editor.picked()
        assertEquals("Video · 5 Nov 2026, 15:30", video.displayName)
        assertEquals(MediaKind.Video, video.kind)
        assertEquals(takenAt, video.takenAtMillis)

        // The project keeps the name: with the file gone, it still says which one to find.
        file.delete()
        editor.recheckMedia()
        val state = editor.awaitState { video.id in it.missingMedia }
        val missing = missingFiles(state.project!!, state.missingMedia).single()
        assertEquals("Video · 5 Nov 2026, 15:30", missing.asset.displayName)
    }

    @Test
    @Config(qualifiers = "pl")
    fun withoutThePickersDateTheVideoIsNamedByTheDateItRecorded() = inWarsaw {
        // A picker that doesn't know the date taken: the date in the file names it.
        val (editor, _) = pickVideo(answersDateTaken = false)

        val video = editor.picked()
        assertEquals("Film · 5 lis 2026, 15:30", video.displayName)
        assertEquals(takenAt, video.takenAtMillis)
    }

    private fun inWarsaw(test: () -> Unit) {
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Europe/Warsaw"))
        try {
            test()
        } finally {
            TimeZone.setDefault(zone)
        }
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
            reversedVideos = ReversedVideos(context, cache),
            decodedSounds = DecodedSounds(context, cache),
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
