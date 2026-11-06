package tech.idct.clipra.feature.export

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
import tech.idct.clipra.core.data.project.ProjectFiles
import tech.idct.clipra.core.data.project.ProjectRepository
import tech.idct.clipra.core.data.settings.SettingsRepository
import tech.idct.clipra.core.engine.media.MediaCache
import tech.idct.clipra.core.model.Clip
import tech.idct.clipra.core.model.ClipType
import tech.idct.clipra.core.model.Layer
import tech.idct.clipra.core.model.MediaAsset
import tech.idct.clipra.core.model.MediaKind
import tech.idct.clipra.core.model.MediaRef
import tech.idct.clipra.core.model.Project
import tech.idct.clipra.core.model.export.LeftOutClip
import tech.idct.clipra.monetization.Entitlement
import tech.idct.clipra.monetization.ads.AdConsent
import tech.idct.clipra.monetization.ads.AdsGateway
import tech.idct.clipra.monetization.ads.ConsentGateway
import tech.idct.clipra.monetization.ads.ExportAds
import tech.idct.clipra.monetization.billing.BillingGateway
import tech.idct.clipra.monetization.billing.LaunchResult
import tech.idct.clipra.monetization.billing.ProStore
import tech.idct.clipra.monetization.billing.PurchaseUpdate
import tech.idct.clipra.monetization.billing.PurchaseVerifier
import tech.idct.clipra.monetization.billing.StorePurchase
import java.io.File

/**
 * Export to Gallery with media missing (#212). The real view model, with the real project files
 * and engine classes, checks every original before the export starts. Until the user says what
 * to do (export anyway or cancel), nothing starts and the settings aren't saved.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ExportMissingMediaTest {
    @get:Rule
    val folder = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val sec = 1_000_000L
    private lateinit var files: ProjectFiles
    private lateinit var originals: File
    private lateinit var project: Project

    /** The project ids the export was handed over for. */
    private val started = mutableListOf<String>()

    @Before
    fun setUp() {
        files = ProjectFiles(folder.newFolder("projects"))
        originals = folder.newFolder("originals")
        // Two videos on L1, a photo overlay on L2 and music on L3, as in the issue.
        project = Project(
            id = "lisbon",
            name = "Lisbon weekend",
            media = listOf(
                asset("tram", MediaKind.Video),
                asset("alfama", MediaKind.Video),
                asset("stamp", MediaKind.Photo),
                asset("fado", MediaKind.Audio),
            ),
            layers = listOf(
                Layer(
                    "L1",
                    clips = listOf(
                        clip("tram", ClipType.Video, 0),
                        clip("alfama", ClipType.Video, 4),
                    ),
                ),
                Layer("L2", clips = listOf(clip("stamp", ClipType.Photo, 2))),
                Layer("L3", clips = listOf(clip("fado", ClipType.Audio, 0, seconds = 8))),
            ),
        )
        files.write(project)
    }

    @After
    fun tearDown() = scope.cancel()

    private fun asset(id: String, kind: MediaKind) = MediaAsset(
        id = id,
        uri = Uri.fromFile(original(id)).toString(),
        kind = kind,
        displayName = "$id.media",
        durationUs = 8 * sec,
        hasAudio = kind != MediaKind.Photo,
    ).also { original(id).writeBytes(ByteArray(64)) }

    private fun original(id: String) = File(originals, "$id.media")

    private fun clip(media: String, type: ClipType, start: Long, seconds: Long = 4) =
        Clip("$media-clip", type, start * sec, seconds * sec, MediaRef(media, 0, seconds * sec))

    @Test
    fun aDeletedOriginalIsListedAndNothingStarts() {
        original("stamp").delete()
        val export = export()

        export.start { started += it }
        val state = export.awaitState { it.leftOut.isNotEmpty() }

        assertEquals(
            listOf(
                LeftOutClip(
                    "stamp-clip",
                    ClipType.Photo,
                    2,
                    2 * sec,
                    6 * sec,
                    "stamp",
                    "stamp.media",
                ),
            ),
            state.leftOut,
        )
        assertTrue(started.isEmpty())
        assertNull(files.read(project.id)!!.exportSettings)
    }

    @Test
    fun exportAnywayStartsWithoutThem() {
        original("stamp").delete()
        original("fado").delete()
        val export = export()

        export.start { started += it }
        val asked = export.awaitState { it.leftOut.isNotEmpty() }.leftOut
        assertEquals(listOf("fado-clip", "stamp-clip"), asked.map { it.clipId })
        export.exportAnyway { started += it }
        await { started.isNotEmpty() }

        assertEquals(listOf(project.id), started)
        assertTrue(export.state.value.leftOut.isEmpty())
        // Remembered as for any other export (#58).
        assertNotNull(files.read(project.id)!!.exportSettings)
    }

    @Test
    fun cancelKeepsTheExportUnstartedUntilTheFileIsBack() {
        original("alfama").delete()
        val export = export()

        export.start { started += it }
        export.awaitState { it.leftOut.isNotEmpty() }
        export.keepLeftOut()
        assertTrue(export.state.value.leftOut.isEmpty())
        repeat(20) { shadowOf(Looper.getMainLooper()).idle() }
        assertTrue(started.isEmpty())

        // The file is back: the next tap exports at once.
        original("alfama").writeBytes(ByteArray(64))
        export.start { started += it }
        await { started.isNotEmpty() }
        assertEquals(listOf(project.id), started)
        assertTrue(export.state.value.leftOut.isEmpty())
    }

    @Test
    fun withEveryOriginalInPlaceTheExportStartsAtOnce() {
        val export = export()

        export.start { started += it }
        await { started.isNotEmpty() }

        assertEquals(listOf(project.id), started)
        assertTrue(export.state.value.leftOut.isEmpty())
    }

    @Test
    fun aClipOnAHiddenAndMutedLayerIsNotMissed() {
        val quiet = project.copy(
            layers = project.layers.map {
                if (it.id == "L1") it.copy(hidden = true, muted = true) else it
            },
        )
        files.write(quiet)
        original("tram").delete()
        val export = export()

        export.start { started += it }
        await { started.isNotEmpty() }

        assertEquals(listOf(project.id), started)
    }

    /** The view model as the Export screen gets it, once it has loaded the project. */
    private fun export(): ExportViewModel {
        val settings = SettingsRepository(store("settings"))
        val proStore = ProStore(
            NoBilling,
            PurchaseVerifier("", trustUnsigned = true),
            store("pro"),
            scope,
        )
        val entitlement = object : Entitlement {
            override val isPro: Flow<Boolean> = flowOf(false)
        }
        val exportAds = ExportAds(
            ads = NoAds,
            consent = AdConsent(NoConsent, entitlement),
            entitlement = entitlement,
            online = { false },
            clock = { 0L },
            scope = scope,
        )
        return ExportViewModel(
            context = context,
            cache = MediaCache(folder.newFolder()),
            projects = ProjectRepository(files, NoIndex, clock = { 0L }, io = Dispatchers.IO),
            proStore = proStore,
            settings = settings,
            exportAds = exportAds,
            savedState = SavedStateHandle(mapOf("projectId" to project.id)),
        ).also { model -> model.awaitState { it.project != null } }
    }

    private fun store(name: String) = PreferenceDataStoreFactory.create(scope = scope) {
        File(folder.root, "$name.preferences_pb")
    }

    private fun ExportViewModel.awaitState(done: (ExportUiState) -> Boolean): ExportUiState {
        await { done(state.value) }
        return state.value
    }

    /** Runs the main looper until [done], while the work off the main thread ends. */
    private fun await(done: () -> Boolean) {
        repeat(500) {
            shadowOf(Looper.getMainLooper()).idle()
            if (done()) return
            Thread.sleep(10)
        }
        error("Never got there")
    }

    private object NoBilling : BillingGateway {
        override val updates: Flow<PurchaseUpdate> = emptyFlow()

        override suspend fun queryPurchases(): List<StorePurchase>? = null

        override suspend fun proPrice(): String? = null

        override suspend fun launchPurchase(activity: Activity) = LaunchResult.Unavailable

        override suspend fun acknowledge(token: String) = false
    }

    private object NoAds : AdsGateway {
        override suspend fun preload() = Unit

        override fun stop() = Unit

        override suspend fun show(
            activity: Activity,
            timeoutMs: Long,
            stillWatching: () -> Boolean,
        ) = false
    }

    private object NoConsent : ConsentGateway {
        override suspend fun update(activity: Activity) = false

        override suspend fun showFormIfRequired(activity: Activity) = Unit

        override suspend fun showPrivacyOptions(activity: Activity) = Unit

        override val canRequestAds = false
        override val privacyOptionsRequired = false
    }

    /** Home's index, which Export doesn't read. */
    private object NoIndex : ProjectIndexDao {
        override fun observeAll(): Flow<List<ProjectIndexEntry>> = flowOf(emptyList())

        override suspend fun get(id: String): ProjectIndexEntry? = null

        override suspend fun upsert(entry: ProjectIndexEntry) = Unit

        override suspend fun delete(id: String) = Unit
    }
}
