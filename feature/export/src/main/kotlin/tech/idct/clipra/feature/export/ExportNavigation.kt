package tech.idct.clipra.feature.export

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import tech.idct.clipra.core.designsystem.component.ReadableWidth
import tech.idct.clipra.core.designsystem.placeholder.Board
import tech.idct.clipra.core.engine.export.ExportManager
import tech.idct.clipra.core.engine.export.ExportState
import tech.idct.clipra.monetization.billing.ProStore
import javax.inject.Inject

/**
 * Export for [projectId]; null opens the placeholder board (debug screen index). [playheadUs] is
 * where the editor's playhead was: the cover's frame (#59).
 */
@Serializable
data class ExportRoute(val projectId: String? = null, val playheadUs: Long = 0)

/** The running export; [projectId] starts one, null shows the one in progress. */
@Serializable
data class ExportProgressRoute(val projectId: String? = null)

@Serializable
data object ExportDoneRoute

/**
 * The boards this module builds, from design/README.md ("Export with two ads").
 */
object ExportBoards {
    val export = Board(id = "Export", title = R.string.export_title_export, route = ExportRoute())
    val exportProgress = Board(
        id = "ExportProgress",
        title = R.string.export_title_export_progress,
        route = ExportProgressRoute(),
    )
    val exportDone = Board(
        id = "ExportDone",
        title = R.string.export_title_export_done,
        route = ExportDoneRoute,
    )

    val all = listOf(export, exportProgress, exportDone)
}

/** Where the export screens lead; the app connects them. */
class ExportActions(
    val onBack: () -> Unit,
    val onStarted: (projectId: String) -> Unit,
    /** Leaves the progress screen; the export carries on. */
    val onKeepEditing: () -> Unit,
    val onDone: () -> Unit,
    val onPro: () -> Unit,
)

/** Registers this module's destinations. [screen] draws a board until its real screen is built. */
fun NavGraphBuilder.exportScreens(screen: @Composable (Board) -> Unit, actions: ExportActions) {
    composable<ExportRoute> { entry ->
        if (entry.toRoute<ExportRoute>().projectId == null) {
            screen(ExportBoards.export)
        } else {
            ExportRoute(actions)
        }
    }
    composable<ExportProgressRoute> { ExportProgressRoute(actions) }
    composable<ExportDoneRoute> { SavedRoute(screen, actions) }
}

@Composable
private fun ExportRoute(actions: ExportActions, viewModel: ExportViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val project = state.project ?: return
    ReadableWidth {
        ExportScreen(
            project = project,
            settings = state.settings,
            support = state.support,
            onChange = viewModel::change,
            onExport = { viewModel.start(actions.onStarted) },
            onBack = actions.onBack,
            ads = if (state.pro) null else ExportAdsLine(state.proPrice, actions.onPro),
        )
    }
}

@Composable
private fun ExportProgressRoute(
    actions: ExportActions,
    viewModel: ExportProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val startAd by viewModel.startAd.collectAsStateWithLifecycle()
    val finishAd by viewModel.finishAd.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val askNotifications =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        // The notification tells you when the video is in the Gallery.
        if (Build.VERSION.SDK_INT >=
            33
        ) {
            askNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
    LaunchedEffect(startAd) {
        if (startAd) context.findActivity()?.let(viewModel::showStartAd)
    }
    // Without Ad 2 to come, a finished export opens the saved video at once.
    LaunchedEffect(state, finishAd) {
        if (state is ExportState.Done && finishAd == false) actions.onDone()
    }
    LifecycleResumeEffect(Unit) {
        viewModel.setVisible(true)
        onPauseOrDispose { viewModel.setVisible(false) }
    }
    ReadableWidth {
        ExportProgressScreen(
            state = state,
            onKeepEditing = actions.onKeepEditing,
            onPause = viewModel::pause,
            onResume = viewModel::resume,
            onCancel = {
                viewModel.cancel()
                actions.onKeepEditing()
            },
            finished = finishAd == true,
            onSeeVideo = { context.findActivity()?.let { viewModel.seeVideo(it, actions.onDone) } },
        )
    }
}

@HiltViewModel
class SavedViewModel @Inject constructor(private val exports: ExportManager, proStore: ProStore) :
    ViewModel() {
    val state: StateFlow<ExportState> = exports.state
    val pro: Flow<Boolean> = proStore.isPro
    val proPrice: StateFlow<String?> = proStore.price

    /** The finished export has been seen; the next one starts fresh. */
    override fun onCleared() = exports.dismiss()
}

@Composable
private fun SavedRoute(
    screen: @Composable (Board) -> Unit,
    actions: ExportActions,
    viewModel: SavedViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val pro by viewModel.pro.collectAsStateWithLifecycle(initialValue = true)
    val proPrice by viewModel.proPrice.collectAsStateWithLifecycle()
    val saved = state as? ExportState.Done
    if (saved == null) {
        // Opened from the screen index without an export.
        screen(ExportBoards.exportDone)
        return
    }
    val context = LocalContext.current
    val uri = Uri.parse(saved.uri)
    ReadableWidth {
        SavedScreen(
            saved = saved,
            showPro = !pro,
            proPrice = proPrice,
            onShare = { shareVideo(context, uri) },
            onOpenGallery = { openInGallery(context, uri) },
            onPro = actions.onPro,
            onBackToEditing = actions.onKeepEditing,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
